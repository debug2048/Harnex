package com.harnex.gateway.im;

import java.util.List;
import java.util.regex.Pattern;

/** Deliberately parses answers only; never infers tool approval from ordinary chat. */
final class ImAnswerParser {
    private static final Pattern NUMBER = Pattern.compile("^(?:我)?(?:选择|选)?(?:第)?([0-9]{1,2}|[一二三四五六七八九十两])(?:个|项|号)?[。.!！]?$", Pattern.CASE_INSENSITIVE);
    static String choice(String text, List<?> options) {
        String value = text.trim();
        for (Object option : options) if (String.valueOf(option).equals(value)) return value;
        value = value.replaceFirst("^(?:<@[^>]+>|@[^\\s]+)\\s+", "");
        for (Object option : options) if (String.valueOf(option).equals(value)) return value;
        int index = -1;
        var match = NUMBER.matcher(value);
        if (match.matches()) {
            String number = match.group(1);
            if (number.matches("[0-9]+")) index = Integer.parseInt(number) - 1;
            else index = "两".equals(number) ? 1 : "一二三四五六七八九十".indexOf(number);
        } else if (value.matches("(?i)^(?:选|选择)?[A-Z]$")) {
            index = Character.toUpperCase(value.charAt(value.length() - 1)) - 'A';
        }
        return index >= 0 && index < options.size() ? String.valueOf(options.get(index)) : null;
    }
    static boolean looksLikeChoice(String text) { return NUMBER.matcher(text.trim()).matches() || text.trim().matches("(?i)^(?:选|选择)?[A-Z]$"); }
}
