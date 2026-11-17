package com.harnex.provider.search;

/**
 * One web-search hit. Deliberately minimal — {@code title} + {@code url} + a short {@code snippet} are all the model
 * needs to decide which URL to {@code web_fetch} next. The backend is responsible for clipping {@code snippet} to a
 * sane length; the tool's {@code BudgetEnforcer} still applies the final output cap.
 */
public record SearchResult(String title, String url, String snippet) {
}
