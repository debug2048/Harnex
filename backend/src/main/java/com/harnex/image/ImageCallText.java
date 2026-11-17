package com.harnex.image;

import java.util.Locale;
import java.util.regex.Pattern;

/** Detects malformed image-call replies for a terminal error; never interprets or executes their arguments. */
public final class ImageCallText {
    private static final Pattern CALL_TEXT = Pattern.compile(
        "(?is)^(?:(?:call|invoke|run|execute)(?:\\s+(?:the\\s+)?(?:tool|function))?|tool\\s+(?:call|request)|调用|执行)\\s+[`'\"]?image_(?:generate|edit)\\b.*");
    private static final Pattern XML_CALL = Pattern.compile(
        "(?is)^(?:(?:<invoke\\s*>\\s*)?<tool\\s+name\\s*=\\s*['\"]image_(?:generate|edit)['\"]\\s*>"
            + "|<tool_call\\s*>\\s*<tool_name\\s*>\\s*image_(?:generate|edit)\\s*</tool_name\\s*>).*");
    private ImageCallText() {}

    public static boolean isMalformedInvocation(String input, String output) {
        // A user discussing the API may deliberately request precisely this text.
        String userText = input.toLowerCase(Locale.ROOT).replace("\\_", "_");
        if (userText.contains(MediaInvocationService.TOOL) || userText.contains(MediaInvocationService.EDIT_TOOL)) return false;
        String text = output.strip();
        if (text.length() > 16_000) return false;
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline < 0 || !text.endsWith("```")) return false;
            text = text.substring(newline + 1, text.length() - 3).strip();
        }
        text = text.replace("\\_", "_");
        return text.contains("prompt") && (XML_CALL.matcher(text).matches()
            || CALL_TEXT.matcher(text).matches());
    }
}
