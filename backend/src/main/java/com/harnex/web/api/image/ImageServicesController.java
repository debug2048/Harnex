package com.harnex.web.api.image;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.harnex.execution.ScopeIdentity;
import com.harnex.image.ImageOperationRecord;
import com.harnex.image.ImageOperationStore;
import com.harnex.image.ImageServiceException;
import com.harnex.image.ImageServiceRegistry;

@RestController
@RequestMapping("/api/v1")
public final class ImageServicesController {
    private final ImageServiceRegistry services;
    private final ImageOperationStore operations;
    private final ScopeIdentity scope;
    public ImageServicesController(ImageServiceRegistry services, ImageOperationStore operations, ScopeIdentity scope) {
        this.services = services; this.operations = operations; this.scope = scope;
    }
    @GetMapping("/image-services") public ImageServiceRegistry.Catalog catalog() { return services.catalog(); }
    @GetMapping("/image-operations/{id}") public ResponseEntity<ImageOperationRecord> operation(@PathVariable String id) {
        return operations.find(scope.scopeId(), id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @ExceptionHandler(ImageServiceException.class) public ResponseEntity<?> failure(ImageServiceException failure) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of("code", failure.code(), "message", failure.getMessage())));
    }
}
