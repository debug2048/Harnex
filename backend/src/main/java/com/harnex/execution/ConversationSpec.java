package com.harnex.execution;

/** Conversation has a fixed text/media flow and cannot select workspace tools or Agent controls. */
public record ConversationSpec(Action action, TextTarget text, String imageServiceId, ImageSpec image)
    implements ExecutionSpec {
    public ConversationSpec {
        if (action == null) throw new IllegalArgumentException("conversation action is required");
        boolean explicitImage = action == Action.IMAGE_GENERATE || action == Action.IMAGE_EDIT;
        if (explicitImage && (image == null || text != null || imageServiceId != null)) {
            throw new IllegalArgumentException("explicit image actions require image and cannot include text or imageServiceId");
        }
        if (!explicitImage && image != null) throw new IllegalArgumentException("text/AUTO cannot include image");
        if (action == Action.TEXT && imageServiceId != null) {
            throw new IllegalArgumentException("TEXT cannot select an image service");
        }
        if (action == Action.IMAGE_EDIT && image.sourceArtifactIds().isEmpty()) {
            throw new IllegalArgumentException("IMAGE_EDIT requires sourceArtifactIds");
        }
        if (action == Action.IMAGE_GENERATE && !image.sourceArtifactIds().isEmpty()) {
            throw new IllegalArgumentException("source images require IMAGE_EDIT");
        }
    }

    @Override public Kind kind() { return Kind.CONVERSATION; }
    public enum Action { TEXT, AUTO, IMAGE_GENERATE, IMAGE_EDIT }
}
