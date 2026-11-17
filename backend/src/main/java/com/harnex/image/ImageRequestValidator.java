package com.harnex.image;

import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import com.harnex.artifact.ArtifactRecord;
import com.harnex.artifact.ArtifactStore;
import com.harnex.execution.ImageSpec;

/** Validates source ownership and supported parameters before admitting a potentially paid image operation. */
@Component
public final class ImageRequestValidator {
    private final ArtifactStore assets;
    public ImageRequestValidator(ArtifactStore assets) { this.assets = assets; }
    public ImageSpec resolve(String scopeId, ImageServiceSnapshot service, ImageSpec requested) {
        var caps = service.capabilities();
        boolean edit = !requested.sourceArtifactIds().isEmpty();
        if (edit ? !caps.edit() : !caps.generate()) fail("image_action_unsupported", "This image service does not support the requested action");
        if (requested.sourceArtifactIds().size() > caps.maxSources()) fail("image_sources_unsupported", "Too many source images for this service");
        ImageSpec.Parameters fallback = service.defaults(), supplied = requested.parameters();
        ImageSpec.Parameters parameters = new ImageSpec.Parameters(supplied.count() == null ? fallback.count() == null ? 1 : fallback.count() : supplied.count(),
            choose(supplied.size(), fallback.size(), "auto"), choose(supplied.quality(), fallback.quality(), "auto"),
            choose(supplied.format(), fallback.format(), "png"), choose(supplied.background(), fallback.background(), "auto"));
        if (parameters.count() > caps.maxCount()) fail("image_parameters_unsupported", "Image count exceeds the configured limit of " + caps.maxCount());
        if (!validSize(parameters.size()) || !caps.sizes().isEmpty() && !caps.sizes().contains(parameters.size()))
            fail("image_parameters_unsupported", "Image size must be auto or supported WIDTHxHEIGHT dimensions: " + parameters.size());
        if (!caps.qualities().contains(parameters.quality())) fail("image_parameters_unsupported", "Unsupported image quality: " + parameters.quality());
        if (!caps.formats().contains(parameters.format())) fail("image_parameters_unsupported", "Unsupported image format: " + parameters.format());
        if (!caps.backgrounds().contains(parameters.background())) fail("image_parameters_unsupported", "Unsupported image background: " + parameters.background());
        NativeImageOptions.validate(service.profile(), parameters);
        if ("transparent".equals(parameters.background()) && "jpeg".equals(parameters.format())) fail("image_parameters_unsupported", "Transparent backgrounds require PNG or WebP");
        for (String source : requested.sourceArtifactIds()) {
            ArtifactRecord asset = source(scopeId, source, caps.maxSourceBytes());
            if (NativeImageOptions.QWEN.equals(service.profile()) && (asset.width() > 8000 || asset.height() > 8000
                || (long)asset.width() > (long)asset.height() * 10 || (long)asset.height() > (long)asset.width() * 10)) {
                fail("image_source_unsupported", "Qwen source images must be at most 8000 pixels per side and within a 10:1 aspect ratio");
            }
        }
        if (requested.maskArtifactId() != null) {
            if (!caps.mask()) fail("image_mask_unsupported", "The selected image service does not support masks");
            ArtifactRecord mask = source(scopeId, requested.maskArtifactId(), caps.maxSourceBytes());
            ArtifactRecord first = source(scopeId, requested.sourceArtifactIds().get(0), caps.maxSourceBytes());
            if (!"image/png".equals(mask.mime()) || !mask.width().equals(first.width()) || !mask.height().equals(first.height())) {
                fail("image_mask_invalid", "The mask must be a PNG with the same dimensions as the first source image");
            }
            try (var bytes = assets.open(scopeId, mask.artifactId()); var image = ImageIO.createImageInputStream(bytes.stream())) {
                var readers = ImageIO.getImageReaders(image);
                if (!readers.hasNext()) fail("image_mask_invalid", "The mask cannot be decoded");
                var reader = readers.next();
                try {
                    reader.setInput(image, true, true);
                    var type = reader.getRawImageType(0);
                    if (type == null || !type.getColorModel().hasAlpha()) fail("image_mask_invalid", "The mask PNG must have an alpha channel");
                } finally { reader.dispose(); }
            } catch (IOException failure) { fail("image_mask_invalid", "The mask is unavailable or invalid"); }
        }
        return new ImageSpec(service.serviceId(), parameters, requested.sourceArtifactIds(), requested.maskArtifactId());
    }
    private ArtifactRecord source(String scope, String id, long maxBytes) {
        ArtifactRecord asset = assets.find(scope, id).orElseThrow(() -> new ImageServiceException("image_source_unavailable", "A selected source image is unavailable"));
        if (asset.availability() != ArtifactRecord.Availability.LOCAL || asset.width() == null || asset.height() == null
            || !List.of("image/png", "image/jpeg", "image/webp").contains(asset.mime()) || asset.bytes() > maxBytes) {
            fail("image_source_unsupported", "A selected source image has an unsupported format or exceeds the service limit");
        }
        try (var ignored = assets.open(scope, id)) { return asset; }
        catch (IOException failure) { throw new ImageServiceException("image_source_unavailable", "A selected source image is unavailable"); }
    }
    private static String choose(String supplied, String defaults, String last) { return supplied != null ? supplied : defaults != null ? defaults : last; }
    private static boolean validSize(String size) {
        if (!size.matches(ImageSpec.Parameters.SIZE_PATTERN)) return false;
        if ("auto".equals(size)) return true;
        String[] dimensions = size.split("x");
        long width = Long.parseLong(dimensions[0]), height = Long.parseLong(dimensions[1]);
        // Match the owned asset decoder's bounds. The selected model enforces its own stricter size rules.
        return width <= 32768 && height <= 32768 && width * height <= 100_000_000L;
    }
    private static void fail(String code, String message) { throw new ImageServiceException(code, message); }
}
