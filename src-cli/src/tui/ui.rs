use super::i18n::{text as tr, tf};
use super::shortcuts::InputKeys;
use ratatui::layout::{Alignment, Constraint, Layout, Margin, Rect};
#[cfg(test)]
use ratatui::style::Color;
use ratatui::style::{Modifier, Style};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{Block, BorderType, Borders, Clear, Padding, Paragraph, Wrap};
use ratatui::Frame;
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

use super::app::{
    App, ChangesState, GoalState, InteractionState, Overlay, PickerState, RestoreState,
    RunInputsState, SnapshotsState, TimelineItem, TimelineKind, RAIL_MIN_WIDTH,
};

pub use super::theme::Theme;

pub fn draw(frame: &mut Frame<'_>, app: &App, theme: Theme) {
    if app.mouse.paint(frame.buffer_mut()) {
        let full = frame.area();
        let footer = Rect::new(full.x, full.bottom().saturating_sub(1), full.width, 1);
        frame.render_widget(
            Paragraph::new(tf!(
                "Selection | Right-click / Ctrl+C: copy | Esc: clear",
                "已选择 | 右键 / Ctrl+C：复制 | Esc：取消选择"
            ))
            .style(theme.base_style()),
            footer,
        );
        return;
    }
    let full = frame.area();
    frame.render_widget(Block::default().style(theme.base_style()), full);
    let area = Rect {
        width: full.width.saturating_sub(1),
        ..full
    };
    let composer_height = composer_height(app, area.width).min(area.height.saturating_sub(5));
    let rows = Layout::vertical([
        Constraint::Length(2),
        Constraint::Min(3),
        Constraint::Length(composer_height),
        Constraint::Length(1),
    ])
    .split(area);

    render_header(frame, rows[0], app, theme);
    let transcript = if full.width >= RAIL_MIN_WIDTH && app.details_open {
        let columns = Layout::horizontal([
            Constraint::Min(70),
            Constraint::Length((full.width / 4).clamp(31, 38)),
        ])
        .split(rows[1]);
        render_rail(frame, columns[1], app, theme);
        columns[0]
    } else {
        rows[1]
    };
    render_transcript(frame, transcript, app, theme);
    render_composer(frame, rows[2], app, theme);
    render_footer(frame, rows[3], app, theme);
    if let Some(overlay) = app.overlay.as_ref() {
        render_overlay(frame, area, overlay, app, theme);
    }
    let regions = if app.overlay.is_some() {
        vec![area]
    } else {
        vec![
            transcript.inner(Margin {
                horizontal: 2,
                vertical: 1,
            }),
            rows[2].inner(Margin {
                horizontal: 2,
                vertical: 1,
            }),
        ]
    };
    app.mouse.capture(frame.buffer_mut(), regions);
}

fn render_header(frame: &mut Frame<'_>, area: Rect, app: &App, theme: Theme) {
    let title = if area.width < 90 {
        truncate(&app.workspace.name, 24)
    } else {
        truncate(
            &format!("{} | {}", app.workspace.name, app.thread_label()),
            54,
        )
    };
    let status_style = task_status_style(app.run_status(), theme);
    let metadata = format!(
        "{} | {} | ",
        if app.agent_available {
            tr(&app.permission_mode)
        } else {
            tr("Conversation")
        },
        truncate(&app.actual_model(), if area.width < 90 { 16 } else { 24 }),
    );
    let status = tr(app.run_status());
    let git_label = if app.agent_available {
        app.workspace_git.label()
    } else {
        None
    };
    let title_budget = (area.width as usize).saturating_sub(
        10 + UnicodeWidthStr::width(metadata.as_str()) + UnicodeWidthStr::width(status),
    );
    let branch = if area.width >= 90 {
        git_label
            .as_ref()
            .map(|label| {
                format!(
                    " | git: {}",
                    truncate(label, (title_budget.saturating_sub(8) / 2).min(32))
                )
            })
            .unwrap_or_default()
    } else {
        String::new()
    };
    let title = truncate(
        &title,
        title_budget.saturating_sub(UnicodeWidthStr::width(branch.as_str())),
    );
    let used = 8
        + UnicodeWidthStr::width(title.as_str())
        + UnicodeWidthStr::width(branch.as_str())
        + UnicodeWidthStr::width(metadata.as_str())
        + UnicodeWidthStr::width(status);
    let gap = " ".repeat((area.width as usize).saturating_sub(used).max(1));
    let line = Line::from(vec![
        Span::styled(
            " harnex ",
            Style::default()
                .fg(theme.foreground)
                .add_modifier(Modifier::BOLD),
        ),
        Span::styled(title, Style::default().fg(theme.foreground)),
        Span::styled(branch, Style::default().fg(theme.muted)),
        Span::raw(gap),
        Span::styled(metadata, Style::default().fg(theme.muted)),
        Span::styled(status, status_style),
        Span::raw(" "),
    ]);
    let mut lines = vec![line];
    // The header already reserves two rows; use its spare row in narrow terminals.
    if area.width < 90 {
        if let Some(label) = git_label {
            lines.push(Line::styled(
                format!(
                    " git: {}",
                    truncate(&label, area.width.saturating_sub(7) as usize)
                ),
                Style::default().fg(theme.muted),
            ));
        }
    }
    frame.render_widget(Paragraph::new(lines), area);
}

fn render_transcript(frame: &mut Frame<'_>, area: Rect, app: &App, theme: Theme) {
    let inner = area.inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    if inner.width == 0 || inner.height == 0 {
        return;
    }
    if app.timeline.is_empty() {
        app.scroll.position(0, inner.height, inner.width);
        render_empty(frame, inner, app, theme);
        return;
    }

    let mut lines = Vec::new();
    for item in &app.timeline {
        if item.kind == TimelineKind::User && !lines.is_empty() {
            lines.push(Line::raw(""));
        }
        if item.kind == TimelineKind::Thinking && !app.show_thinking {
            lines.push(Line::styled(
                tr("  Thinking collapsed | Ctrl+O details"),
                Style::default().fg(theme.muted),
            ));
            continue;
        }
        append_timeline_item(&mut lines, item, theme, inner.width);
        lines.push(Line::raw(""));
    }
    render_scrolled_lines(frame, inner, lines, &app.scroll);
}

fn render_scrolled_lines(
    frame: &mut Frame<'_>,
    inner: Rect,
    lines: Vec<Line<'_>>,
    scroll: &super::scroll::TranscriptScroll,
) {
    // Paragraph's u16 scroll offset cannot address a long tool transcript.
    // Slice the physical rows first, then render only the requested viewport.
    let rows = lines
        .iter()
        .map(|line| {
            if line.width() <= inner.width as usize {
                1
            } else {
                Paragraph::new(line.clone())
                    .wrap(Wrap { trim: false })
                    .line_count(inner.width)
            }
        })
        .collect::<Vec<_>>();
    let top = scroll.position(rows.iter().sum(), inner.height, inner.width);
    let mut skipped = 0;
    let mut start = 0;
    while start < rows.len() && skipped + rows[start] <= top {
        skipped += rows[start];
        start += 1;
    }
    let within = top - skipped;
    let mut visible_rows = 0;
    let mut end = start;
    while end < rows.len() && visible_rows < within + inner.height as usize {
        visible_rows += rows[end];
        end += 1;
    }
    let visible = lines
        .into_iter()
        .skip(start)
        .take(end - start)
        .collect::<Vec<_>>();
    frame.render_widget(
        Paragraph::new(Text::from(visible))
            .wrap(Wrap { trim: false })
            .scroll((within.min(u16::MAX as usize) as u16, 0)),
        inner,
    );
}

fn render_empty(frame: &mut Frame<'_>, area: Rect, app: &App, theme: Theme) {
    let top = area.y + area.height.saturating_sub(10) / 3;
    let empty = Rect::new(
        area.x,
        top,
        area.width,
        area.height.saturating_sub(top - area.y),
    );
    let readiness = if !app.agent_available {
        tr("Ready for a conversation")
    } else if app.attached {
        tr("Connected to your workspace")
    } else {
        tr("Ready in your workspace")
    };
    let lines = vec![
        Line::styled(
            "Harnex",
            Style::default()
                .fg(theme.foreground)
                .add_modifier(Modifier::BOLD),
        ),
        Line::styled(
            if app.agent_available {
                tr("Project Agent")
            } else {
                tr("Personal conversations")
            },
            Style::default().fg(theme.foreground),
        ),
        Line::styled(readiness, Style::default().fg(theme.secondary)),
        Line::raw(""),
        Line::styled(
            tr("Ctrl+P command menu | F1 shortcuts"),
            Style::default()
                .fg(theme.accent)
                .add_modifier(Modifier::BOLD),
        ),
        Line::styled(
            tr("History | Models & effort | Settings"),
            Style::default().fg(theme.secondary),
        ),
        Line::styled(
            tr("F2 model | F3 effort | Ctrl+N new"),
            Style::default().fg(theme.foreground),
        ),
        Line::styled(
            if app.agent_available {
                tr("Shift+Tab approval mode")
            } else {
                tr("Ask a question, write, or describe an image")
            },
            Style::default().fg(theme.secondary),
        ),
        Line::styled(
            tr("Ctrl+X → Q exit | press in sequence"),
            Style::default().fg(theme.muted),
        ),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .style(theme.base_style())
            .alignment(Alignment::Center),
        empty,
    );
}

fn append_timeline_item(
    lines: &mut Vec<Line<'static>>,
    item: &TimelineItem,
    theme: Theme,
    width: u16,
) {
    let first_line = lines.len();
    match item.kind {
        TimelineKind::User => {
            let title = item
                .title
                .strip_prefix("You | ")
                .map(|channel| tf!("You | {channel}", "你 | {channel}"))
                .unwrap_or_else(|| tr(&item.title).to_string());
            lines.push(
                Line::from(vec![
                    Span::styled("│ ", Style::default().fg(theme.accent)),
                    Span::styled(
                        title,
                        Style::default()
                            .fg(theme.accent)
                            .add_modifier(Modifier::BOLD),
                    ),
                ])
                .style(Style::default().bg(theme.user_background)),
            );
            append_content(lines, &item.content, theme, width, "│ ", true);
            lines.push(Line::styled(
                "│ ",
                Style::default().fg(theme.accent).bg(theme.user_background),
            ));
        }
        TimelineKind::Assistant => {
            lines.push(Line::from(vec![
                Span::styled(
                    "  Harnex",
                    Style::default()
                        .fg(theme.foreground)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::styled(
                    if item.running {
                        tr("  replying...")
                    } else {
                        ""
                    },
                    Style::default().fg(theme.secondary),
                ),
            ]));
            append_content(lines, &item.content, theme, width, "  ", false);
        }
        TimelineKind::Thinking => {
            lines.push(Line::styled(
                format!("  {}", tr("Thinking")),
                Style::default().fg(theme.secondary),
            ));
            append_content(lines, &item.content, theme, width, "  ", false);
        }
        TimelineKind::Tool => {
            let (status, color) = if item.queued {
                ("QUEUED", theme.secondary)
            } else if item.running {
                ("RUNNING", theme.accent)
            } else if item.failed {
                ("FAILED", theme.danger)
            } else {
                ("COMPLETED", theme.success)
            };
            let label = format!("{} | ", tr("Tool"));
            let status = format!("  [{}]", tr(status));
            let title_width = (width as usize).saturating_sub(
                5 + UnicodeWidthStr::width(label.as_str())
                    + UnicodeWidthStr::width(status.as_str()),
            );
            lines.push(Line::from(vec![
                Span::styled("  ── ", Style::default().fg(theme.border)),
                Span::styled(label, Style::default().fg(theme.secondary)),
                Span::styled(
                    truncate(&item.title, title_width),
                    Style::default()
                        .fg(theme.foreground)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::styled(status, Style::default().fg(color)),
            ]));
            if let Some(purpose) = &item.purpose {
                append_content(lines, purpose, theme, width, "    ", false);
            }
            if let Some((label, input)) = &item.tool_input {
                append_content(
                    lines,
                    input,
                    theme,
                    width,
                    &format!("    {}: ", tr(label)),
                    false,
                );
            }
            if !item.content.is_empty() {
                lines.push(Line::styled(
                    format!("    │ {}", tr("Output")),
                    Style::default().fg(theme.secondary),
                ));
                let mut output = Vec::new();
                append_content(
                    &mut output,
                    &item.content,
                    theme,
                    width.saturating_sub(6),
                    "",
                    false,
                );
                for mut line in output {
                    line.spans
                        .insert(0, Span::styled("    │ ", Style::default().fg(theme.border)));
                    lines.push(line);
                }
            }
        }
        TimelineKind::Notice | TimelineKind::Warning | TimelineKind::Error => {
            let (symbol, color) = match item.kind {
                TimelineKind::Notice => ("-", theme.secondary),
                TimelineKind::Warning => ("!", theme.warning),
                _ => ("x", theme.danger),
            };
            lines.push(Line::from(vec![
                Span::styled(format!("  {symbol} "), Style::default().fg(color)),
                Span::styled(
                    tr(&item.title).to_string(),
                    Style::default().fg(color).add_modifier(Modifier::BOLD),
                ),
            ]));
            append_content(lines, tr(&item.content), theme, width, "    ", false);
        }
    }
    if item.kind == TimelineKind::User {
        for line in &mut lines[first_line..] {
            *line = filled_line(
                line.clone(),
                width,
                Style::default().bg(theme.user_background),
            );
        }
    }
}

fn append_content(
    lines: &mut Vec<Line<'static>>,
    content: &str,
    theme: Theme,
    width: u16,
    indent: &str,
    user: bool,
) {
    let mut in_code = false;
    for source in content.lines() {
        if source.trim_start().starts_with("```") {
            in_code = !in_code;
            continue;
        }
        let (prefix, raw, style) = if in_code {
            (
                indent.to_string(),
                source.to_string(),
                Style::default().fg(theme.foreground).bg(if user {
                    theme.user_background
                } else {
                    theme.inset
                }),
            )
        } else if let Some(heading) = source
            .strip_prefix("### ")
            .or_else(|| source.strip_prefix("## "))
            .or_else(|| source.strip_prefix("# "))
        {
            (
                indent.to_string(),
                heading.to_string(),
                Style::default()
                    .fg(theme.foreground)
                    .add_modifier(Modifier::BOLD),
            )
        } else if let Some(item) = source.strip_prefix("- ") {
            (
                format!("{indent}- "),
                item.to_string(),
                Style::default().fg(theme.foreground),
            )
        } else {
            (
                indent.to_string(),
                source.to_string(),
                Style::default().fg(theme.foreground),
            )
        };
        let wrap_width = (width as usize)
            .saturating_sub(UnicodeWidthStr::width(prefix.as_str()))
            .max(1);
        let wrapped = textwrap::wrap(&raw, wrap_width);
        if wrapped.is_empty() {
            lines.push(Line::raw(prefix));
            continue;
        }
        for (index, part) in wrapped.into_iter().enumerate() {
            let prefix = if index == 0 || user {
                prefix.clone()
            } else {
                " ".repeat(UnicodeWidthStr::width(prefix.as_str()))
            };
            let mut line = Line::from(vec![
                Span::styled(
                    prefix,
                    Style::default().fg(if user { theme.accent } else { theme.foreground }),
                ),
                Span::styled(part.into_owned(), style),
            ]);
            if user || in_code {
                line = filled_line(
                    line,
                    width,
                    Style::default().bg(if user {
                        theme.user_background
                    } else {
                        theme.inset
                    }),
                );
            }
            lines.push(line);
        }
    }
}

fn filled_line(mut line: Line<'static>, width: u16, style: Style) -> Line<'static> {
    let padding = " ".repeat((width as usize).saturating_sub(line.width()));
    line.spans.push(Span::styled(padding, style));
    line.style = line.style.patch(style);
    line
}

fn task_status_style(status: &str, theme: Theme) -> Style {
    Style::default().fg(match status {
        "FAILED" => theme.danger,
        "INCOMPLETE" | "CANCELLED" | "EXPIRED" => theme.warning,
        "COMPLETED" => theme.success,
        "READY" => theme.secondary,
        _ => theme.accent,
    })
}

fn rail_field(
    label: &str,
    value: &str,
    width: u16,
    theme: Theme,
    value_style: Style,
) -> Line<'static> {
    let label = truncate(label, width.saturating_sub(2) as usize);
    let value = truncate(value, (width as usize).saturating_sub(label.width() + 1));
    let gap = " ".repeat(
        (width as usize)
            .saturating_sub(label.width() + value.width())
            .max(1),
    );
    Line::from(vec![
        Span::styled(format!("{label}{gap}"), Style::default().fg(theme.muted)),
        Span::styled(value, value_style),
    ])
}

fn render_rail(frame: &mut Frame<'_>, area: Rect, app: &App, theme: Theme) {
    let block = Block::default()
        .borders(Borders::LEFT)
        .border_style(Style::default().fg(theme.border))
        .style(theme.surface_style());
    let inner = block.inner(area).inner(Margin {
        horizontal: 1,
        vertical: 1,
    });
    frame.render_widget(block, area);
    if inner.is_empty() {
        return;
    }
    let limit = inner.height as usize;
    let value_style = Style::default().fg(theme.foreground);
    let field =
        |label: &str, value: &str| rail_field(label, value, inner.width, theme, value_style);
    let usage = app.usage().filter(|usage| {
        usage.total > 0 || usage.prompt > 0 || usage.completion > 0 || usage.complete
    });
    let usage_rows = if usage.is_some() { 6 } else { 2 };
    let show_model = limit >= 2 + usage_rows + 2;
    // Reserve the numeric rows before spending space on details or blank lines.
    let mut spare = limit.saturating_sub(2 + usage_rows + if show_model { 2 } else { 0 });
    let mut take = |rows: usize, wanted: bool| {
        if wanted && spare >= rows {
            spare -= rows;
            true
        } else {
            false
        }
    };
    let activity = if !app.interactions.is_empty() {
        Some(tf!("Needs you: {}", "待你处理：{}", app.interactions.len()))
    } else {
        app.current_activity()
    };
    let show_activity = take(1, activity.is_some());
    let queued = app
        .run
        .as_ref()
        .map(|run| run.pending_input_count)
        .unwrap_or(0);
    let show_queued = take(1, queued > 0);
    let show_usage_note = take(1, usage.is_some());
    let show_effort = take(1, show_model);
    let model = if app.run.is_some() {
        app.actual_model()
    } else {
        app.selected_model
            .clone()
            .unwrap_or_else(|| tr("auto").to_string())
    };
    let model_lines = textwrap::wrap(&model, inner.width as usize);
    let show_second_model_line = take(1, show_model && model_lines.len() > 1);
    let model_spacing = take(1, show_model);
    let usage_spacing = take(1, true);
    let provider = if let Some(run) = app.run.as_ref() {
        run.actual_provider
            .as_deref()
            .or(run.requested_provider.as_deref())
            .or_else(|| run.execution_spec["text"]["provider"].as_str())
    } else {
        app.selected_provider.as_deref()
    };
    let show_provider = take(1, show_model && provider.is_some());
    let show_cache_write = take(1, usage.is_some_and(|usage| usage.cache_write_input > 0));
    let show_reasoning = take(1, usage.is_some_and(|usage| usage.reasoning > 0));
    let show_goal = take(4, app.goal.is_some());
    let mut lines = vec![
        section(tr("CURRENT TASK"), theme),
        rail_field(
            tr("status"),
            tr(app.run_status()),
            inner.width,
            theme,
            task_status_style(app.run_status(), theme).add_modifier(Modifier::BOLD),
        ),
    ];
    if show_activity {
        lines.push(Line::styled(
            truncate(activity.as_deref().unwrap(), inner.width as usize),
            Style::default().fg(if app.interactions.is_empty() {
                theme.accent
            } else {
                theme.warning
            }),
        ));
    }
    if show_queued {
        lines.push(field(tr("Queued msgs"), &queued.to_string()));
    }
    if show_model {
        if model_spacing {
            lines.push(Line::raw(""));
        }
        lines.push(section(
            tr(if app.run.is_some() {
                "THIS TASK MODEL"
            } else {
                "NEXT TASK CONFIG"
            }),
            theme,
        ));
        let row_count = if show_second_model_line { 2 } else { 1 };
        for (index, part) in model_lines.iter().take(row_count).enumerate() {
            let text = if index + 1 == row_count && model_lines.len() > row_count {
                truncate(&format!("{part}..."), inner.width as usize)
            } else {
                part.to_string()
            };
            lines.push(Line::styled(text, value_style.add_modifier(Modifier::BOLD)));
        }
        if show_provider {
            lines.push(field(tr("Provider"), provider.unwrap()));
        }
        if show_effort {
            // This is the task's requested configuration, not the next task's selection.
            let effort = app
                .run
                .as_ref()
                .map(|run| {
                    run.execution_spec["text"]["reasoningEffort"]
                        .as_str()
                        .unwrap_or("auto")
                })
                .unwrap_or(app.selected_effort.as_str());
            lines.push(field(tr("Reasoning effort"), tr(effort)));
        }
    }
    if usage_spacing {
        lines.push(Line::raw(""));
    }
    lines.push(section(tr("RUN USAGE"), theme));
    if let Some(usage) = usage {
        lines.extend([
            rail_field(
                tr("total"),
                &format_tokens(usage.total),
                inner.width,
                theme,
                value_style.add_modifier(Modifier::BOLD),
            ),
            field(tr("Input"), &format_tokens(usage.prompt)),
            field(tr("Output"), &format_tokens(usage.completion)),
            field(tr("Cache read"), &format_tokens(usage.cached_input)),
            field(
                tr("Hit rate"),
                &usage
                    .cache_hit_rate()
                    .map(|rate| format!("{rate:.1}%"))
                    .unwrap_or_else(|| "--".into()),
            ),
        ]);
        if show_cache_write {
            lines.push(field(
                tr("Cache write"),
                &format_tokens(usage.cache_write_input),
            ));
        }
        if show_reasoning {
            lines.push(field(tr("reasoning"), &format_tokens(usage.reasoning)));
        }
        if show_usage_note {
            lines.push(Line::styled(
                tr(if usage.complete {
                    "Usage reported"
                } else {
                    "Reported usage so far"
                }),
                Style::default().fg(theme.muted),
            ));
        }
    } else {
        lines.push(Line::styled(
            tr(if app.run.is_none() {
                "Usage appears after a task"
            } else if app.live_run_id().is_some() {
                "Awaiting usage"
            } else {
                "No usage reported"
            }),
            Style::default().fg(theme.muted),
        ));
    }
    if show_goal {
        let goal = app.goal.as_ref().unwrap();
        lines.extend([
            Line::raw(""),
            section(tr("GOAL"), theme),
            field(tr("status"), tr(&goal.status)),
            Line::styled(truncate(&goal.objective, inner.width as usize), value_style),
        ]);
    }
    frame.render_widget(Paragraph::new(lines).style(theme.surface_style()), inner);
}

fn section(label: &str, theme: Theme) -> Line<'static> {
    Line::styled(
        label.to_string(),
        Style::default()
            .fg(theme.secondary)
            .add_modifier(Modifier::BOLD),
    )
}

fn format_tokens(value: u64) -> String {
    let digits = value.to_string();
    let mut text = String::new();
    for (index, character) in digits.chars().enumerate() {
        if index > 0 && (digits.len() - index) % 3 == 0 {
            text.push(',');
        }
        text.push(character);
    }
    text
}

fn fact(label: &str, value: &str, theme: Theme) -> Line<'static> {
    let padding = " ".repeat(14usize.saturating_sub(UnicodeWidthStr::width(label)));
    Line::from(vec![
        Span::styled(
            format!("{label}{padding}"),
            Style::default().fg(theme.muted),
        ),
        Span::styled(truncate(value, 16), Style::default().fg(theme.foreground)),
    ])
}

fn next_task_caption(app: &App, width: usize) -> String {
    let keys = if width >= 76 { " | F2/F3" } else { "" };
    let suffix = format!(" | {}: {}{keys} ", tr("effort"), tr(&app.selected_effort));
    let mut prefix = format!(" {}: ", tr("Next task model"));
    if prefix.width() + suffix.width() + 3 > width {
        prefix = format!(" {}: ", tr("Next model"));
    }
    if prefix.width() + suffix.width() + 3 > width {
        return truncate(tr("F2 model | F3 effort"), width);
    }
    let model = app.selected_model.as_deref().unwrap_or(tr("auto"));
    let model_width = width
        .saturating_sub(prefix.width() + suffix.width())
        .min(48);
    format!("{prefix}{}{suffix}", truncate(model, model_width))
}

fn render_composer(frame: &mut Frame<'_>, area: Rect, app: &App, theme: Theme) {
    let area = area.inner(Margin {
        horizontal: 1,
        vertical: 0,
    });
    let title = if app.submitting_input() {
        tr(" sending... ")
    } else if app.live_run_id().is_some() {
        if area.width < 70 {
            tr(" update ")
        } else {
            tr(" update current task ")
        }
    } else {
        tr(" message ")
    };
    let mut block = Block::default()
        .borders(Borders::ALL)
        .border_type(BorderType::Rounded)
        .padding(Padding::horizontal(1))
        .style(theme.surface_style())
        .border_style(Style::default().fg(if app.overlay.is_some() {
            theme.border
        } else {
            theme.accent
        }))
        .title(Span::styled(title, Style::default().fg(theme.muted)));
    let inner = block.inner(area);
    app.editor_width.set(inner.width.max(1) as usize);
    let next_width = (area.width as usize).saturating_sub(title.width() + 5);
    let next = next_task_caption(app, next_width);
    block = block.title_top(
        Line::from(Span::styled(next, Style::default().fg(theme.secondary)))
            .alignment(Alignment::Right),
    );
    if app.composer.text.len() > 500 || app.composer.text.contains('\n') {
        let mut hint = tf!(
            " {} lines | {} chars | Ctrl+X → V preview ",
            " {} 行 | {} 字符 | Ctrl+X → V 全文预览 ",
            app.composer
                .text
                .bytes()
                .filter(|byte| *byte == b'\n')
                .count()
                + 1,
            app.composer.text.chars().count()
        );
        if area.width >= 100 {
            hint.push_str(tr("| Ctrl+X → E editor "));
        }
        block = block.title_bottom(Span::styled(
            truncate(&hint, area.width.saturating_sub(4) as usize),
            Style::default().fg(theme.muted),
        ));
    }
    frame.render_widget(block, area);
    let display = if app.composer.text.is_empty() {
        Text::from(Line::styled(
            if !app.agent_available {
                tr("Hint: ask a question...")
            } else if app.live_run_id().is_some() {
                tr("Hint: add to the current task...")
            } else {
                tr("Hint: describe a task...")
            },
            Style::default()
                .fg(theme.placeholder)
                .add_modifier(Modifier::ITALIC),
        ))
    } else {
        Text::raw(app.composer.text.clone())
    };
    let (cursor_row, cursor_col) =
        composer_cursor(&app.composer.text, app.composer.cursor, inner.width.max(1));
    let visible_start = cursor_row.saturating_sub(inner.height.saturating_sub(1));
    frame.render_widget(
        Paragraph::new(display)
            .style(theme.surface_style())
            .wrap(Wrap { trim: false })
            .scroll((visible_start, 0)),
        inner,
    );
    if app.overlay.is_none() {
        frame.set_cursor_position((
            inner.x + cursor_col.min(inner.width.saturating_sub(1)),
            inner.y
                + cursor_row
                    .saturating_sub(visible_start)
                    .min(inner.height.saturating_sub(1)),
        ));
    }
}

fn render_footer(frame: &mut Frame<'_>, area: Rect, app: &App, theme: Theme) {
    // Never write the physical last column: some Windows hosts wrap it immediately.
    let area = Rect {
        width: area.width.saturating_sub(1),
        ..area
    };
    let help = if area.width >= 40 {
        tr("F1 shortcuts")
    } else {
        "F1"
    };
    let help_width = (UnicodeWidthStr::width(help) as u16 + 2).min(area.width);
    let help_area = Rect::new(
        area.right().saturating_sub(help_width),
        area.y,
        help_width,
        area.height,
    );
    frame.render_widget(
        Paragraph::new(help)
            .alignment(Alignment::Right)
            .style(Style::default().fg(theme.accent)),
        help_area,
    );
    let area = Rect {
        width: area.width.saturating_sub(help_width + 1),
        ..area
    };
    let budget = area.width.saturating_sub(2) as usize;
    let text = if app.leader.is_some() {
        format!(
            " {}",
            truncate(
                tr("Release Ctrl+X, then: Q exit | M model | R effort | Esc cancel"),
                budget
            )
        )
    } else if let Some((notice, _)) = app.notice.as_ref() {
        format!(" {}", truncate(tr(notice), budget))
    } else if app.overlay.is_none() && !app.scroll.is_following() {
        let hint = if app.unread > 0 {
            tf!(
                "Reading history | {} new | Ctrl+End latest",
                "回看记录 | {} 条新消息 | Ctrl+End 最新",
                app.unread
            )
        } else {
            tr("Reading history | PgUp/PgDn | Ctrl+End latest").into()
        };
        format!(" {}", truncate(&hint, budget))
    } else if app.overlay.is_some() {
        format!(
            " {}",
            truncate(tr("Esc back | Ctrl+P command home"), budget)
        )
    } else {
        let live = app.live_run_id().is_some();
        let mut left = tr(app.input_keys.footer_hint(live));
        let compact_right = tr("ctrl+p commands");
        let mut right = if app.unread > 0 {
            tf!(
                "{} new | pgdn follow",
                "{} 条新消息 | pgdn 跟随",
                app.unread
            )
        } else if app.can_switch_scope {
            tf!(
                "f6 {} | ctrl+p commands",
                "f6 {} | ctrl+p 命令",
                if app.agent_available {
                    tr("conversation")
                } else {
                    tr("agent")
                }
            )
        } else {
            compact_right.to_string()
        };
        if UnicodeWidthStr::width(left) + UnicodeWidthStr::width(right.as_str()) + 2 > budget {
            right = compact_right.to_string();
        }
        if UnicodeWidthStr::width(left) + UnicodeWidthStr::width(right.as_str()) + 2 > budget {
            right.clear();
        }
        if UnicodeWidthStr::width(left) > budget {
            left = tr(app.input_keys.compact_hint());
        }
        if UnicodeWidthStr::width(left) > budget {
            left = tr(app.input_keys.minimal_hint());
        }
        if UnicodeWidthStr::width(left) > budget {
            left = "";
        }
        if UnicodeWidthStr::width(right.as_str()) > budget {
            right = "Ctrl+P".to_string();
        }
        let right = truncate(&right, budget);
        let gap =
            " ".repeat(budget.saturating_sub(
                UnicodeWidthStr::width(left) + UnicodeWidthStr::width(right.as_str()),
            ));
        format!(" {left}{gap}{right}")
    };
    frame.render_widget(
        Paragraph::new(text).style(Style::default().fg(theme.muted)),
        area,
    );
}

fn render_overlay(frame: &mut Frame<'_>, area: Rect, overlay: &Overlay, app: &App, theme: Theme) {
    let area = match overlay {
        Overlay::Text(_) => centered_percent(area, 94, 92),
        Overlay::Help => centered(area, 88, 28),
        Overlay::Picker(picker) => centered(
            area,
            78,
            (app.picker_entries(picker).len().min(13) as u16 + 8).max(10),
        ),
        Overlay::Changes(_) => centered_percent(area, 90, 86),
        Overlay::RunInputs(_) => centered_percent(area, 78, 70),
        Overlay::Snapshots(_) => centered_percent(area, 82, 78),
        Overlay::Restore(_) => centered_percent(area, 82, 76),
        Overlay::Interaction(state) if state.interaction.kind == "approval" => {
            let width = 76.min(area.width.saturating_sub(2));
            let arguments = state.interaction.payload["arguments"]
                .as_str()
                .unwrap_or("");
            let lines = textwrap::wrap(arguments, width.saturating_sub(8).max(1) as usize).len();
            centered(
                area,
                width,
                (lines.saturating_add(9).min(u16::MAX as usize) as u16).max(11),
            )
        }
        Overlay::Interaction(_) | Overlay::Goal(_) => centered_percent(area, 82, 72),
        Overlay::Todos(_) => centered(area, 72, 22),
        Overlay::Context => centered(area, 76, 22),
        _ => centered(area, 68, 9),
    };
    match overlay {
        Overlay::Loading(_) => render_confirm(frame, area, tr("Loading"), tr("Loading..."), tr("Esc back"), theme),
        Overlay::Help => render_help(frame, area, &app.help_scroll, app.input_keys, theme),
        Overlay::Text(state) => render_text_view(frame, area, state, theme),
        Overlay::Picker(picker) => render_picker(frame, area, picker, app, theme),
        Overlay::Interaction(state) => {
            render_interaction(frame, area, state, app.input_keys, theme)
        }
        Overlay::Changes(state) => {
            render_changes(frame, area, state, theme)
        }
        Overlay::RunInputs(state) => {
            render_run_inputs(frame, area, state, theme)
        }
        Overlay::Snapshots(state) => {
            render_snapshots(frame, area, state, theme)
        }
        Overlay::Restore(state) => {
            render_restore(frame, area, state, theme)
        }
        Overlay::Goal(state) => render_goal(frame, area, state, app.input_keys, theme),
        Overlay::Todos(todos) => render_todos(frame, area, todos, theme),
        Overlay::Context => render_context(frame, area, app, theme),
        Overlay::ConfirmYolo => render_confirm(
            frame,
            area,
            tr("Enable full access?"),
            tr("Harnex may run tools without asking. Runtime denylist and path safety still apply."),
            tr("Enter/y enable | Esc/n back"),
            theme,
        ),
        Overlay::ConfirmExit => render_confirm(
            frame,
            area,
            tr("Exit this terminal?"),
            tr("Your draft is kept. Exiting stops runtimes started by this terminal and their tasks. Shared runtimes stay open."),
            tr("c cancel + exit | e/Enter exit | Esc back"),
            theme,
        ),
    }
    if !app.navigation.is_empty() {
        let hint = tr(" Esc back | Ctrl+P command home ");
        let width = (UnicodeWidthStr::width(hint) as u16).min(area.width.saturating_sub(4));
        frame.render_widget(
            Paragraph::new(truncate(hint, width as usize))
                .style(Style::default().fg(theme.accent).bg(theme.surface)),
            Rect::new(
                area.right().saturating_sub(width + 2),
                area.bottom().saturating_sub(1),
                width,
                1,
            ),
        );
    }
}

fn overlay_block<'a>(title: &'a str, theme: Theme) -> Block<'a> {
    let title_color = if [
        tr("Approval required"),
        tr("Plan review"),
        tr("Confirm restore"),
        tr("Enable full access?"),
    ]
    .contains(&title)
    {
        theme.warning
    } else {
        theme.foreground
    };
    Block::default()
        .borders(Borders::ALL)
        .border_type(BorderType::Rounded)
        .border_style(Style::default().fg(theme.border))
        .style(theme.surface_style())
        .title(Span::styled(
            format!(" {title} "),
            Style::default()
                .fg(title_color)
                .add_modifier(Modifier::BOLD),
        ))
}

fn render_help(
    frame: &mut Frame<'_>,
    area: Rect,
    scroll: &super::scroll::TranscriptScroll,
    input_keys: InputKeys,
    theme: Theme,
) {
    frame.render_widget(Clear, area);
    let block = overlay_block(tr("Keyboard"), theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let rows = super::shortcuts::ROWS;
    let mut lines = vec![
        Line::styled(
            tr("Ctrl+X → Q: press Ctrl+X, release, then Q"),
            Style::default().fg(theme.foreground),
        ),
        Line::styled(
            tr("Ctrl+P opens all commands, including history and settings"),
            Style::default().fg(theme.secondary),
        ),
        Line::styled(tr(input_keys.label()), Style::default().fg(theme.accent)),
        Line::raw(""),
    ];
    if cfg!(target_os = "macos") {
        lines.push(Line::styled(
            tr("Mac: Ctrl means Control; function keys may need Fn"),
            Style::default().fg(theme.secondary),
        ));
        lines.push(Line::styled(
            tr("Modified Enter needs terminal support; Ctrl+X → E opens an editor"),
            Style::default().fg(theme.secondary),
        ));
        lines.push(Line::raw(""));
    }
    lines.extend(
        rows.into_iter()
            .map(|(key, label)| {
                let label = tr(label);
                Line::from(vec![
                    Span::styled(format!("{key:<23} "), Style::default().fg(theme.accent)),
                    Span::styled(label, Style::default().fg(theme.foreground)),
                ])
            })
            .collect::<Vec<_>>(),
    );
    let body = Rect {
        height: inner.height.saturating_sub(1),
        ..inner
    };
    render_scrolled_lines(frame, body, lines, scroll);
    frame.render_widget(
        Paragraph::new(tr("Up/Down scroll | PgUp/PgDn page | Esc back")),
        Rect::new(inner.x, inner.bottom().saturating_sub(1), inner.width, 1),
    );
}

fn render_text_view(frame: &mut Frame<'_>, area: Rect, state: &super::app::TextView, theme: Theme) {
    frame.render_widget(Clear, area);
    let block = overlay_block(&state.title, theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 1,
        vertical: 0,
    });
    frame.render_widget(block, area);
    let body = Rect {
        height: inner.height.saturating_sub(2),
        ..inner
    };
    let lines = state
        .text
        .split('\n')
        .map(|line| Line::styled(line, Style::default().fg(theme.foreground)))
        .collect();
    render_scrolled_lines(frame, body, lines, &state.scroll);
    let hint = if state.draft {
        tr("PgUp/PgDn scroll | c copy | e edit | Esc back")
    } else {
        tr("PgUp/PgDn scroll | c copy | Esc back")
    };
    frame.render_widget(
        Paragraph::new(truncate(hint, inner.width as usize))
            .style(Style::default().fg(theme.muted)),
        Rect::new(inner.x, inner.bottom().saturating_sub(1), inner.width, 1),
    );
}

fn picker_title(kind: &super::app::PickerKind) -> &str {
    use super::app::PickerKind;
    match kind {
        PickerKind::InputHistory => tr("Input history"),
        PickerKind::CommandGroup(group) => tr(group.title()),
        PickerKind::Commands => tr("Commands"),
        PickerKind::Language => tr("Language / 语言"),
        PickerKind::Themes => tr("Theme"),
        PickerKind::Permissions => tr("Permission mode"),
        PickerKind::Threads => tr("Threads"),
        PickerKind::Files => tr("Reference a file"),
        PickerKind::Models => tr("Choose model"),
        PickerKind::Efforts => tr("Reasoning effort"),
        PickerKind::InputKeys => tr("Newline and send keys"),
        PickerKind::Skills => tr("Reference a skill"),
        PickerKind::Toolsets => tr("Choose toolset"),
        PickerKind::Slash => tr("Slash commands"),
    }
}

fn render_picker(frame: &mut Frame<'_>, area: Rect, picker: &PickerState, app: &App, theme: Theme) {
    use super::app::{CommandGroup, PickerKind};
    frame.render_widget(Clear, area);
    let mut path = app
        .navigation
        .iter()
        .filter_map(|overlay| match overlay {
            Overlay::Picker(parent) => Some(picker_title(&parent.kind)),
            _ => None,
        })
        .collect::<Vec<_>>();
    path.push(picker_title(&picker.kind));
    let title = truncate(&path.join(" > "), area.width.saturating_sub(4) as usize);
    let block = overlay_block(&title, theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let scope = match picker.kind {
        PickerKind::InputKeys => tr("Text input only; Enter still confirms menus").into(),
        PickerKind::InputHistory => tr("Enter restores the draft; it does not send").to_string(),
        PickerKind::Models => tr("Ctrl+F favorite | F3 effort | Shift+F2 recent").to_string(),
        PickerKind::Efforts => {
            if app.models_loading {
                tr("Loading model capabilities...").into()
            } else if !app.models_ready {
                tr("Capabilities unavailable; reopen F3 to retry").into()
            } else if let Some(model) = app.selected_catalog_model() {
                if app.effort_choices().is_empty() {
                    tr("This model has no selectable reasoning levels").into()
                } else if app.agent_available && model.function_tool_reasoning_effort.is_some() {
                    tf!(
                        "{} | tool mode requires {}",
                        "{} | 工具模式仅支持 {}",
                        model.model,
                        model.function_tool_reasoning_effort.as_deref().unwrap()
                    )
                } else {
                    let default = if model.default_reasoning_effort.is_empty() {
                        "auto"
                    } else {
                        &model.default_reasoning_effort
                    };
                    tf!(
                        "{} | default: {} | next task",
                        "{} | 默认：{} | 下次任务生效",
                        model.model,
                        default
                    )
                }
            } else {
                tr("Choose a model with F2 first").into()
            }
        }
        PickerKind::CommandGroup(CommandGroup::Conversation) => {
            tf!("Conversation: {}", "会话：{}", app.thread_label())
        }
        PickerKind::CommandGroup(CommandGroup::Task) => {
            tf!("Task: {}", "任务：{}", tr(app.run_status()))
        }
        PickerKind::Slash => tr("Choose a command, then add its arguments").to_string(),
        PickerKind::Commands => tr("Browse by scope, or type to search all commands").to_string(),
        _ => String::new(),
    };
    let mut lines = vec![
        filled_line(
            Line::from(vec![
                Span::styled(
                    if matches!(picker.kind, PickerKind::Slash) {
                        "/ "
                    } else {
                        "> "
                    },
                    Style::default().fg(theme.accent),
                ),
                Span::styled(
                    if picker.query.is_empty() {
                        tr("Search commands").to_string()
                    } else {
                        picker.query.clone()
                    },
                    Style::default().fg(if picker.query.is_empty() {
                        theme.secondary
                    } else {
                        theme.foreground
                    }),
                ),
            ]),
            inner.width,
            Style::default().fg(theme.foreground).bg(theme.inset),
        ),
        Line::styled(
            truncate(&scope, inner.width as usize),
            Style::default().fg(theme.muted),
        ),
        Line::raw(""),
    ];
    let entries = app.picker_entries(picker);
    let selected_reason = entries.get(picker.selected).and_then(|(id, _)| {
        matches!(
            picker.kind,
            PickerKind::Commands | PickerKind::CommandGroup(_)
        )
        .then(|| app.command_reason(id))
        .flatten()
    });
    let visible = usize::from(inner.height.saturating_sub(4)).max(1);
    let start = picker.selected.saturating_sub(visible - 1);
    for (index, (id, label)) in entries.into_iter().enumerate().skip(start).take(visible) {
        let selected = index == picker.selected;
        let palette = matches!(
            picker.kind,
            PickerKind::Commands | PickerKind::CommandGroup(_)
        );
        let disabled = palette && app.command_reason(&id).is_some();
        let style = if selected {
            theme.selection_style()
        } else if disabled {
            Style::default().fg(theme.muted)
        } else {
            Style::default().fg(theme.foreground)
        };
        let mut spans = vec![Span::styled(if selected { "> " } else { "  " }, style)];
        let budget = inner.width.saturating_sub(2) as usize;
        if matches!(picker.kind, PickerKind::Slash) {
            let command = format!("/{id}  ");
            spans.push(Span::styled(
                command.clone(),
                style.add_modifier(Modifier::BOLD),
            ));
            spans.push(Span::styled(
                truncate(
                    &label,
                    budget.saturating_sub(UnicodeWidthStr::width(command.as_str())),
                ),
                style,
            ));
        } else if palette {
            let suffix = if disabled {
                tr(" (unavailable)")
            } else if matches!(id.as_str(), "conversation" | "task" | "display") {
                " >"
            } else {
                ""
            };
            spans.push(Span::styled(
                truncate(
                    &label,
                    budget.saturating_sub(UnicodeWidthStr::width(suffix)),
                ),
                style,
            ));
            spans.push(Span::styled(suffix, style));
        } else {
            let current = (matches!(picker.kind, PickerKind::Permissions)
                && id == app.permission_mode)
                || (matches!(picker.kind, PickerKind::Themes) && id == app.theme_name)
                || (matches!(picker.kind, PickerKind::Efforts) && id == app.selected_effort)
                || (matches!(picker.kind, PickerKind::InputKeys) && id == app.input_keys.id())
                || (matches!(picker.kind, PickerKind::Models)
                    && id
                        == format!(
                            "{}:{}",
                            app.selected_provider.as_deref().unwrap_or(""),
                            app.selected_model.as_deref().unwrap_or("")
                        ));
            let suffix = if current { tr(" (current)") } else { "" };
            spans.push(Span::styled(
                truncate(&format!("{label}{suffix}"), budget),
                style,
            ));
        }
        lines.push(filled_line(Line::from(spans), inner.width, style));
    }
    if lines.len() == 3 {
        lines.push(Line::styled(
            if matches!(picker.kind, PickerKind::Threads) && picker.query.is_empty() {
                tr("No conversations yet. Esc returns to your draft.")
            } else {
                tr("No matches")
            },
            Style::default().fg(theme.muted),
        ));
    }
    let body = Rect {
        height: inner.height.saturating_sub(1),
        ..inner
    };
    frame.render_widget(Paragraph::new(lines), body);
    let hint = selected_reason
        .map(tr)
        .unwrap_or_else(|| tr("Up/Down select | Enter confirm | Esc back"));
    frame.render_widget(
        Paragraph::new(truncate(hint, inner.width as usize)).style(Style::default().fg(
            if selected_reason.is_some() {
                theme.warning
            } else {
                theme.muted
            },
        )),
        Rect::new(inner.x, inner.bottom().saturating_sub(1), inner.width, 1),
    );
}

fn render_interaction(
    frame: &mut Frame<'_>,
    area: Rect,
    state: &InteractionState,
    input_keys: InputKeys,
    theme: Theme,
) {
    frame.render_widget(Clear, area);
    let title = match state.interaction.kind.as_str() {
        "approval" => tr("Approval required"),
        "plan_review" => tr("Plan review"),
        _ => tr("Question"),
    };
    let block = overlay_block(title, theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let payload = &state.interaction.payload;
    let mut lines = Vec::new();
    if state.interaction.kind == "approval" {
        let tool = payload
            .get("toolName")
            .and_then(serde_json::Value::as_str)
            .unwrap_or(tr("tool"));
        let arguments = payload
            .get("arguments")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("");
        lines.push(Line::styled(
            tool.to_string(),
            Style::default()
                .fg(theme.foreground)
                .add_modifier(Modifier::BOLD),
        ));
        lines.push(Line::raw(""));
        for line in textwrap::wrap(arguments, inner.width.saturating_sub(2).max(10) as usize) {
            lines.push(filled_line(
                Line::styled(
                    line.into_owned(),
                    Style::default().fg(theme.foreground).bg(theme.inset),
                ),
                inner.width,
                Style::default().bg(theme.inset),
            ));
        }
        lines.push(Line::raw(""));
        let outside_write = payload
            .get("outsideWrite")
            .and_then(serde_json::Value::as_bool)
            .unwrap_or(false);
        let mut actions = vec![
            Span::styled(tr("a allow once"), theme.selection_style()),
            Span::raw("  "),
        ];
        if !outside_write {
            actions.extend([
                Span::styled(
                    tr("w allow this tool"),
                    Style::default().fg(theme.foreground),
                ),
                Span::raw("  "),
            ]);
        }
        actions.push(Span::styled(
            tr("d deny"),
            Style::default().fg(theme.foreground),
        ));
        let body = Rect {
            height: inner.height.saturating_sub(2),
            ..inner
        };
        let paragraph = Paragraph::new(lines).wrap(Wrap { trim: false });
        let max_scroll = paragraph
            .line_count(body.width)
            .saturating_sub(body.height as usize)
            .min(u16::MAX as usize) as u16;
        frame.render_widget(paragraph.scroll((state.scroll.min(max_scroll), 0)), body);
        frame.render_widget(
            Paragraph::new(Line::from(actions)),
            Rect::new(inner.x, inner.bottom().saturating_sub(2), inner.width, 1),
        );
        frame.render_widget(
            Paragraph::new(tr("PgUp/PgDn details | Esc back"))
                .style(Style::default().fg(theme.muted)),
            Rect::new(inner.x, inner.bottom().saturating_sub(1), inner.width, 1),
        );
        return;
    } else {
        let body = payload
            .get(if state.interaction.kind == "plan_review" {
                "plan"
            } else {
                "question"
            })
            .and_then(serde_json::Value::as_str)
            .unwrap_or("");
        for line in body.lines() {
            lines.push(Line::styled(
                line.to_string(),
                Style::default().fg(theme.foreground),
            ));
        }
        if let Some(options) = payload.get("options").and_then(serde_json::Value::as_array) {
            lines.push(Line::raw(""));
            for (index, option) in options
                .iter()
                .filter_map(serde_json::Value::as_str)
                .enumerate()
            {
                lines.push(Line::from(vec![
                    Span::styled(
                        format!("{}  ", index + 1),
                        Style::default().fg(theme.accent),
                    ),
                    Span::raw(option.to_string()),
                ]));
            }
        }
        lines.push(Line::raw(""));
        if state.interaction.kind == "plan_review" && !state.rejecting {
            lines.push(Line::styled(
                tr("a/Enter approve | r reject with feedback"),
                Style::default().fg(theme.accent),
            ));
        } else {
            lines.push(Line::from(vec![
                Span::styled("> ", Style::default().fg(theme.accent)),
                Span::raw(state.input.text.clone()),
            ]));
            lines.push(Line::styled(
                tr(input_keys.submit_hint()),
                Style::default().fg(theme.muted),
            ));
        }
    }
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }), inner);
}

fn render_changes(frame: &mut Frame<'_>, area: Rect, state: &ChangesState, theme: Theme) {
    frame.render_widget(Clear, area);
    if let Some(diff) = state.diff.as_ref() {
        let diff_title = tf!("Diff | {}", "差异 | {}", truncate(&diff.path, 60));
        let block = overlay_block(&diff_title, theme);
        let inner = block.inner(area).inner(Margin {
            horizontal: 1,
            vertical: 1,
        });
        frame.render_widget(block, area);
        let mut lines = vec![
            Line::from(vec![
                Span::styled(
                    format!("+{}", diff.added),
                    Style::default().fg(theme.success),
                ),
                Span::raw("  "),
                Span::styled(
                    format!("-{}", diff.removed),
                    Style::default().fg(theme.danger),
                ),
                if diff.truncated {
                    Span::styled(tr("  truncated"), Style::default().fg(theme.warning))
                } else {
                    Span::raw("")
                },
            ]),
            Line::raw(""),
        ];
        if diff.binary || !diff.available {
            lines.push(Line::styled(
                tr("Binary or unavailable diff"),
                Style::default().fg(theme.warning),
            ));
        } else {
            for source in diff.unified_diff.lines() {
                let style = if source.starts_with('+') && !source.starts_with("+++") {
                    Style::default().fg(theme.success)
                } else if source.starts_with('-') && !source.starts_with("---") {
                    Style::default().fg(theme.danger)
                } else if source.starts_with("@@") {
                    Style::default().fg(theme.accent)
                } else {
                    Style::default().fg(theme.foreground)
                };
                lines.push(Line::styled(source.to_string(), style));
            }
        }
        frame.render_widget(
            Paragraph::new(lines)
                .wrap(Wrap { trim: false })
                .scroll((state.scroll, 0)),
            inner,
        );
        return;
    }

    let block = overlay_block(tr("This run's changes"), theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let mut lines = vec![
        Line::styled(
            tf!(
                "{} | {} files",
                "{} | {} 个文件",
                state.view.repository_type,
                state.view.entries.len()
            ),
            Style::default().fg(theme.muted),
        ),
        Line::raw(""),
    ];
    for (index, entry) in state.view.entries.iter().enumerate() {
        let selected = index == state.selected;
        let style = if selected {
            theme.selection_style()
        } else {
            Style::default().fg(theme.foreground)
        };
        lines.push(Line::from(vec![
            Span::styled(if selected { "> " } else { "  " }, style),
            Span::styled(format!("{:<9}", tr(&entry.status)), style),
            Span::styled(
                truncate(&entry.path, inner.width.saturating_sub(28) as usize),
                style,
            ),
            Span::styled(
                format!("  +{} -{}", entry.added, entry.removed),
                if selected {
                    style
                } else {
                    Style::default().fg(theme.muted)
                },
            ),
        ]));
    }
    if state.view.entries.is_empty() {
        lines.push(Line::styled(
            tr("No files changed by this run"),
            Style::default().fg(theme.muted),
        ));
    } else {
        lines.push(Line::raw(""));
        lines.push(Line::styled(
            tr("Up/Down select | Enter diff | Esc back"),
            Style::default().fg(theme.muted),
        ));
    }
    frame.render_widget(Paragraph::new(lines), inner);
}

fn render_run_inputs(frame: &mut Frame<'_>, area: Rect, state: &RunInputsState, theme: Theme) {
    frame.render_widget(Clear, area);
    let block = overlay_block(tr("Run inputs"), theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let mut lines = vec![
        Line::styled(
            tr("Updates to this task and messages to run after completion"),
            Style::default().fg(theme.muted),
        ),
        Line::raw(""),
    ];
    for (index, input) in state.inputs.iter().enumerate() {
        let selected = index == state.selected;
        let style = if selected {
            theme.selection_style()
        } else {
            Style::default().fg(theme.foreground)
        };
        lines.push(Line::from(vec![
            Span::styled(if selected { "> " } else { "  " }, style),
            Span::styled(
                format!(
                    "#{:<3} {:<12} {:<18}",
                    input.sequence,
                    if input.mode == "QUEUE" {
                        tr("After task")
                    } else {
                        tr("Update")
                    },
                    match input.status.as_str() {
                        "QUEUED" => tr("Waiting"),
                        "APPLYING" => tr("Processing"),
                        "APPLIED" => tr("Delivered"),
                        "HELD" => tr("On hold"),
                        "CANCELLED" => tr("Not applied"),
                        "REJECTED" => tr("Could not deliver"),
                        "SUPERSEDED" => tr("Replaced"),
                        _ => tr("Pending"),
                    }
                ),
                style,
            ),
            Span::styled(truncate(&input.text.replace('\n', " ↵ "), 42), style),
        ]));
        if selected {
            if let Some(reason) = input.terminal_reason.as_deref() {
                lines.push(Line::styled(
                    format!("    {reason}"),
                    Style::default().fg(theme.muted),
                ));
            }
        }
    }
    if state.inputs.is_empty() {
        lines.push(Line::styled(
            tr("No inputs for this run"),
            Style::default().fg(theme.muted),
        ));
    } else {
        lines.extend([
            Line::raw(""),
            Line::styled(
                tr("Up/Down select | d withdraw queued/held | Esc back"),
                Style::default().fg(theme.muted),
            ),
        ]);
    }
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }), inner);
}

fn render_snapshots(frame: &mut Frame<'_>, area: Rect, state: &SnapshotsState, theme: Theme) {
    frame.render_widget(Clear, area);
    let block = overlay_block(tr("Restore points"), theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let mut lines = vec![
        Line::styled(
            tr("Restore is refused while a Run is active; branch remains safe."),
            Style::default().fg(theme.muted),
        ),
        Line::raw(""),
    ];
    for (index, snapshot) in state.snapshots.iter().enumerate() {
        let selected = index == state.selected;
        let style = if selected {
            theme.selection_style()
        } else {
            Style::default().fg(theme.foreground)
        };
        lines.push(Line::from(vec![
            Span::styled(if selected { "> " } else { "  " }, style),
            Span::styled(
                format!(
                    "#{:<4} {:<12}",
                    snapshot.seq,
                    truncate(tr(&snapshot.kind), 11)
                ),
                style,
            ),
            Span::styled(truncate(&snapshot.label, 28), style),
            Span::styled(
                tf!("  {} msgs", "  {} 条消息", snapshot.msg_count),
                if selected {
                    style
                } else {
                    Style::default().fg(theme.muted)
                },
            ),
        ]));
    }
    if state.snapshots.is_empty() {
        lines.push(Line::styled(
            tr("No restore points"),
            Style::default().fg(theme.muted),
        ));
    } else {
        lines.extend([
            Line::raw(""),
            Line::styled(
                tr("c conversation | r conversation + files | b branch | Esc back"),
                Style::default().fg(theme.accent),
            ),
        ]);
    }
    frame.render_widget(Paragraph::new(lines), inner);
}

fn render_restore(frame: &mut Frame<'_>, area: Rect, state: &RestoreState, theme: Theme) {
    frame.render_widget(Clear, area);
    let block = overlay_block(tr("Confirm restore"), theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let mut lines = vec![
        fact(
            tr("snapshot"),
            &format!("#{} {}", state.snapshot.seq, state.snapshot.label),
            theme,
        ),
        fact(tr("mode"), tr(&state.plan.mode), theme),
        fact(tr("coverage"), tr(&state.plan.file_coverage), theme),
        fact(tr("files"), &state.plan.files.len().to_string(), theme),
        fact(
            tr("conflicts"),
            &state.plan.conflicts.len().to_string(),
            theme,
        ),
        Line::raw(""),
    ];
    for conflict in state.plan.conflicts.iter().take(8) {
        lines.push(Line::from(vec![
            Span::styled("! ", Style::default().fg(theme.warning)),
            Span::styled(conflict.path.clone(), Style::default().fg(theme.foreground)),
            Span::styled(
                format!("  {}", conflict.reason.as_deref().unwrap_or(tr("conflict"))),
                Style::default().fg(theme.muted),
            ),
        ]));
    }
    for warning in state.plan.warnings.iter().take(5) {
        lines.push(Line::styled(
            format!("! {warning}"),
            Style::default().fg(theme.warning),
        ));
    }
    lines.extend([
        Line::raw(""),
        Line::styled(
            tr("Enter restore | Esc back"),
            Style::default().fg(theme.accent),
        ),
    ]);
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }), inner);
}

fn render_goal(
    frame: &mut Frame<'_>,
    area: Rect,
    state: &GoalState,
    input_keys: InputKeys,
    theme: Theme,
) {
    frame.render_widget(Clear, area);
    let block = overlay_block(tr("Goal"), theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let goal = &state.goal;
    let mut lines = vec![
        Line::styled(
            goal.objective.clone(),
            Style::default()
                .fg(theme.foreground)
                .add_modifier(Modifier::BOLD),
        ),
        Line::raw(""),
        fact(tr("status"), tr(&goal.status), theme),
        fact(tr("runs"), &goal.run_ids.len().to_string(), theme),
        fact(
            tr("turn budget"),
            &goal.budget.max_goal_turns.to_string(),
            theme,
        ),
        fact(
            tr("token budget"),
            &format_count(goal.budget.token_budget),
            theme,
        ),
    ];
    if goal.budget.cost_budget > 0.0 {
        lines.push(fact(
            tr("cost budget"),
            &format!("${:.4}", goal.budget.cost_budget),
            theme,
        ));
    }
    if state.reviewing {
        lines.extend([
            Line::raw(""),
            Line::from(vec![
                Span::styled(tr("feedback > "), Style::default().fg(theme.accent)),
                Span::raw(state.feedback.text.clone()),
            ]),
            Line::styled(
                tr(input_keys.submit_hint()),
                Style::default().fg(theme.muted),
            ),
        ]);
    } else {
        lines.extend([
            Line::raw(""),
            Line::styled(
                tr("p pause | r resume | c cancel | a confirm review | f feedback | Esc back"),
                Style::default().fg(theme.accent),
            ),
        ]);
    }
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }), inner);
}

fn render_todos(frame: &mut Frame<'_>, area: Rect, todos: &[super::api::TodoView], theme: Theme) {
    frame.render_widget(Clear, area);
    let block = overlay_block(tr("Todo progress"), theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let mut lines = Vec::new();
    for todo in todos {
        let (symbol, color) = match todo.status.as_str() {
            "completed" => ("✓", theme.success),
            "in_progress" => ("*", theme.accent),
            _ => ("o", theme.muted),
        };
        lines.push(Line::from(vec![
            Span::styled(format!("{symbol} "), Style::default().fg(color)),
            Span::styled(
                if todo.status == "in_progress" {
                    todo.active_form
                        .clone()
                        .unwrap_or_else(|| todo.content.clone())
                } else {
                    todo.content.clone()
                },
                Style::default().fg(theme.foreground),
            ),
        ]));
    }
    if todos.is_empty() {
        lines.push(Line::styled(
            tr("No todos"),
            Style::default().fg(theme.muted),
        ));
    }
    lines.extend([
        Line::raw(""),
        Line::styled(tr("Esc back"), Style::default().fg(theme.muted)),
    ]);
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }), inner);
}

fn render_context(frame: &mut Frame<'_>, area: Rect, app: &App, theme: Theme) {
    frame.render_widget(Clear, area);
    let block = overlay_block(tr("Context"), theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    let mut lines = vec![
        fact(tr("workspace"), &app.workspace.name, theme),
        fact(tr("root"), &truncate(&app.workspace.root, 52), theme),
        fact(tr("thread"), app.thread_label(), theme),
        fact(tr("model"), &app.actual_model(), theme),
        fact(
            tr("mode"),
            if app.agent_available {
                tr(&app.permission_mode)
            } else {
                tr("Conversation")
            },
            theme,
        ),
        fact(
            tr("toolsets"),
            &if app.selected_toolsets.is_empty() {
                tr("default").to_string()
            } else {
                app.selected_toolsets.join(",")
            },
            theme,
        ),
        fact(
            tr("runtime"),
            if app.attached {
                tr("attached client")
            } else {
                tr("owned domain")
            },
            theme,
        ),
    ];
    if let Some(usage) = app.usage() {
        lines.extend([
            Line::raw(""),
            section(tr("USAGE"), theme),
            fact(tr("prompt"), &format_count(usage.prompt), theme),
            fact(tr("completion"), &format_count(usage.completion), theme),
            fact(tr("cached"), &format_count(usage.cached_input), theme),
            fact(tr("reasoning"), &format_count(usage.reasoning), theme),
            fact(tr("total"), &format_count(usage.total), theme),
            fact(
                tr("complete"),
                if usage.complete {
                    tr("yes")
                } else {
                    tr("partial")
                },
                theme,
            ),
        ]);
    }
    lines.extend([
        Line::raw(""),
        Line::styled(tr("Esc back"), Style::default().fg(theme.muted)),
    ]);
    frame.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }), inner);
}

fn render_confirm(
    frame: &mut Frame<'_>,
    area: Rect,
    title: &str,
    body: &str,
    actions: &str,
    theme: Theme,
) {
    frame.render_widget(Clear, area);
    let block = overlay_block(title, theme);
    let inner = block.inner(area).inner(Margin {
        horizontal: 2,
        vertical: 1,
    });
    frame.render_widget(block, area);
    frame.render_widget(
        Paragraph::new(vec![
            Line::styled(body.to_string(), Style::default().fg(theme.foreground)),
            Line::raw(""),
            Line::styled(actions.to_string(), Style::default().fg(theme.accent)),
        ])
        .wrap(Wrap { trim: true }),
        inner,
    );
}

fn composer_height(app: &App, width: u16) -> u16 {
    let inner = width.saturating_sub(6).max(1);
    let (rows, _) = composer_cursor(&app.composer.text, app.composer.text.len(), inner);
    rows.saturating_add(3).clamp(3, 10)
}

fn composer_cursor(text: &str, cursor: usize, width: u16) -> (u16, u16) {
    let mut row = 0_u16;
    let mut col = 0_u16;
    for grapheme in text[..cursor.min(text.len())].graphemes(true) {
        if grapheme == "\n" {
            row = row.saturating_add(1);
            col = 0;
            continue;
        }
        let grapheme_width = UnicodeWidthStr::width(grapheme) as u16;
        if col > 0 && col.saturating_add(grapheme_width) > width {
            row = row.saturating_add(1);
            col = 0;
        }
        col = col.saturating_add(grapheme_width);
    }
    (row, col)
}

fn centered(area: Rect, width: u16, height: u16) -> Rect {
    let width = width
        .min(area.width.saturating_sub(2))
        .max(20)
        .min(area.width);
    let height = height
        .min(area.height.saturating_sub(2))
        .max(5)
        .min(area.height);
    Rect::new(
        area.x + area.width.saturating_sub(width) / 2,
        area.y + area.height.saturating_sub(height) / 2,
        width,
        height,
    )
}

fn centered_percent(area: Rect, width: u16, height: u16) -> Rect {
    centered(
        area,
        area.width.saturating_mul(width) / 100,
        area.height.saturating_mul(height) / 100,
    )
}

fn truncate(value: &str, max: usize) -> String {
    if UnicodeWidthStr::width(value) <= max {
        return value.to_string();
    }
    let suffix = ".".repeat(max.min(3));
    let budget = max.saturating_sub(suffix.len());
    let mut out = String::new();
    let mut width = 0;
    for grapheme in value.graphemes(true) {
        let next = UnicodeWidthStr::width(grapheme);
        if width + next > budget {
            break;
        }
        out.push_str(grapheme);
        width += next;
    }
    out.push_str(&suffix);
    out
}

fn format_count(value: u64) -> String {
    if value >= 1_000_000 {
        format!("{:.1}m", value as f64 / 1_000_000.0)
    } else if value >= 1_000 {
        format!("{:.1}k", value as f64 / 1_000.0)
    } else {
        value.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tui::api::{RunView, StreamEvent, WorkspaceView};
    use crate::tui::app::{TimelineItem, TimelineKind};
    use ratatui::backend::TestBackend;
    use ratatui::Terminal;

    #[test]
    fn rendered_text_uses_readable_paired_colors_in_both_themes() {
        use super::super::api::InteractionView;
        use super::super::theme::tests::contrast;
        let mut app = App::new(
            WorkspaceView {
                name: "Harnex".into(),
                root: "D:/project".into(),
                ..Default::default()
            },
            vec![],
            None,
            None,
            false,
        );
        app.thread_title = "Review terminal appearance".into();
        for name in ["dark", "light"] {
            let theme = Theme::resolve(name, false, false);
            for width in [80, 120, 160] {
                app.sync_rail_width(width);
                for stage in ["welcome", "conversation", "commands", "approval"] {
                    app.timeline.clear();
                    app.overlay = None;
                    app.run = None;
                    if stage != "welcome" {
                        app.run = Some(RunView {
                            status: "COMPLETED".into(),
                            terminal: true,
                            actual_model: Some("example-model".into()),
                            usage: Some(super::super::api::UsageView {
                                prompt: 12000,
                                completion: 800,
                                total: 12800,
                                cached_input: 9000,
                                cache_write_input: 1000,
                                reasoning: 200,
                                complete: true,
                            }),
                            ..Default::default()
                        });
                        app.timeline = vec![
                            TimelineItem {
                                kind: TimelineKind::User,
                                title: "You".into(),
                                content: "Check terminal cleanup. 检查进程回收。".into(),
                                purpose: None,
                                tool_input: None,
                                queued: false,
                                sequence: 0,
                                content_complete: false,
                                call_id: None,
                                running: false,
                                failed: false,
                            },
                            TimelineItem {
                                kind: TimelineKind::Assistant,
                                title: "Harnex".into(),
                                content: "I will inspect the owner and run the regression test."
                                    .into(),
                                purpose: None,
                                tool_input: None,
                                queued: false,
                                sequence: 0,
                                content_complete: false,
                                call_id: None,
                                running: false,
                                failed: false,
                            },
                            TimelineItem {
                                kind: TimelineKind::Tool,
                                title: "file_read".into(),
                                content: "OwnedRuntime::terminate()".into(),
                                purpose: None,
                                tool_input: Some(("Path".into(), "src-cli/src/main.rs".into())),
                                queued: false,
                                sequence: 0,
                                content_complete: false,
                                call_id: None,
                                running: false,
                                failed: false,
                            },
                            TimelineItem {
                                kind: TimelineKind::Tool,
                                title: "shell".into(),
                                content: "2 passed; 0 failed".into(),
                                purpose: None,
                                tool_input: Some((
                                    "Command".into(),
                                    "cargo test --manifest-path host-runtime/Cargo.toml".into(),
                                )),
                                queued: false,
                                sequence: 0,
                                content_complete: false,
                                call_id: None,
                                running: false,
                                failed: false,
                            },
                        ];
                    }
                    if stage == "commands" {
                        app.overlay = Some(Overlay::Picker(PickerState {
                            kind: super::super::app::PickerKind::Commands,
                            query: "".into(),
                            selected: 2,
                        }));
                    } else if stage == "approval" {
                        app.overlay = Some(Overlay::Interaction(InteractionState {
                            scroll: 0,
                            interaction: InteractionView {
                                kind: "approval".into(),
                                payload: serde_json::json!({"toolName":"shell", "arguments":"cargo test --locked", "outsideWrite":true}),
                                ..Default::default()
                            },
                            input: super::super::app::Composer::default(),
                            rejecting: false,
                        }));
                    }
                    let mut terminal = Terminal::new(TestBackend::new(width, 28)).unwrap();
                    terminal.draw(|frame| draw(frame, &app, theme)).unwrap();
                    let buffer = terminal.backend().buffer();
                    if stage == "commands" {
                        let selected_width = buffer
                            .content
                            .chunks(width as usize)
                            .map(|row| {
                                row.iter()
                                    .filter(|cell| cell.bg == theme.selection_background)
                                    .count()
                            })
                            .max()
                            .unwrap();
                        assert!(
                            selected_width >= 60,
                            "selection must fill a row, not just its label"
                        );
                    }
                    for cell in &buffer.content {
                        if cell
                            .symbol()
                            .chars()
                            .all(|ch| ch.is_whitespace() || "│─╭╮╰╯┌┐└┘-+*".contains(ch))
                        {
                            continue;
                        }
                        assert!(
                            matches!((cell.fg, cell.bg), (Color::Rgb(..), Color::Rgb(..))),
                            "{name}/{stage}: unpaired {:?}",
                            cell
                        );
                        assert!(
                            contrast(cell.fg, cell.bg) >= 4.5,
                            "{name}/{stage}/{width}: low contrast {:?}",
                            cell
                        );
                    }
                    if let Some(directory) = std::env::var_os("HARNEX_TUI_FRAME_DIR") {
                        let directory = std::path::PathBuf::from(directory);
                        std::fs::create_dir_all(&directory).unwrap();
                        let color = |value: Color| match value {
                            Color::Rgb(r, g, b) => format!("#{r:02x}{g:02x}{b:02x}"),
                            _ => "inherit".into(),
                        };
                        let cells = buffer.content.iter().map(|cell| serde_json::json!({"text":cell.symbol(),"fg":color(cell.fg),"bg":color(cell.bg),"bold":cell.modifier.contains(Modifier::BOLD)})).collect::<Vec<_>>();
                        std::fs::write(
                            directory.join(format!("{name}-{stage}-{width}.json")),
                            serde_json::json!({"width":width,"height":28,"cells":cells})
                                .to_string(),
                        )
                        .unwrap();
                    }
                }
            }
        }
    }

    #[test]
    fn tools_stay_visible_when_completed_and_thinking_is_toggled() {
        let mut app = App::new(WorkspaceView::default(), vec![], None, None, false);
        for (title, content, failed) in [
            ("Read file", "successful tool details", false),
            ("Search", "search details", false),
            ("Build", "compiler error must stay visible", true),
        ] {
            app.timeline.push(TimelineItem {
                kind: TimelineKind::Tool,
                title: title.into(),
                content: content.into(),
                purpose: None,
                tool_input: None,
                queued: false,
                sequence: 0,
                content_complete: false,
                call_id: None,
                running: !failed,
                failed,
            });
        }
        for running in [true, false] {
            for item in &mut app.timeline {
                item.running = running && !item.failed;
            }
            for show_thinking in [false, true] {
                app.show_thinking = show_thinking;
                for width in [60, 100, 160] {
                    let rendered = screen(&app, width, 28);
                    assert!(rendered.contains("successful tool details"));
                    assert!(rendered.contains("search details"));
                    assert!(rendered.contains("compiler error must stay visible"));
                    assert!(!rendered.contains("Ctrl+O details"));
                }
            }
        }
    }

    #[test]
    fn reply_and_sidebar_status_follow_model_and_tool_events() {
        let mut app = App::new(
            WorkspaceView::default(),
            vec![],
            None,
            Some(RunView {
                status: "QUEUED".into(),
                ..Default::default()
            }),
            false,
        );
        app.details_open = true;
        assert!(screen(&app, 140, 36).contains("QUEUED"));
        app.apply_stream(StreamEvent {
            kind: "message_start".into(),
            ..Default::default()
        });
        app.apply_stream(StreamEvent {
            kind: "text_delta".into(),
            data: serde_json::json!({"text":"Checking the workspace."}),
            ..Default::default()
        });
        let replying = screen(&app, 140, 36);
        assert!(!replying.contains("QUEUED"));
        assert!(
            replying.matches("RUNNING").count() >= 2,
            "header and rail must agree"
        );
        assert!(replying.contains("replying..."));
        app.apply_stream(StreamEvent { kind:"tool_use_start".into(), data:serde_json::json!({"id":"call","name":"shell","purpose":"Verify the fix with tests","arguments":"{\"command\":\"cargo test\"}"}), ..Default::default() });
        let executing = screen(&app, 140, 36);
        assert!(!executing.contains("replying..."));
        assert!(executing.contains("── Tool | shell"));
        assert!(executing.contains("Command: cargo test"));
        assert!(executing.contains("Verify the fix with tests"));
        assert!(!executing.contains("QUEUED"));
    }

    #[test]
    fn completed_long_output_can_be_scrolled_from_first_to_last_line() {
        use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
        let mut app = App::new(WorkspaceView::default(), vec![], None, None, false);
        app.apply_stream(StreamEvent {
            kind: "tool_use_start".into(),
            seq: 1,
            data: serde_json::json!({"id":"long","name":"shell"}),
            ..Default::default()
        });
        let output = (0..70_000)
            .map(|index| format!("LOG{index:05}\n"))
            .collect::<String>();
        app.apply_stream(StreamEvent {
            kind: "tool_use_end".into(),
            seq: 2,
            data: serde_json::json!({"id":"long","name":"shell","content":output}),
            ..Default::default()
        });
        assert!(screen(&app, 80, 24).contains("LOG69999"));
        app.handle_key(KeyEvent::new(KeyCode::Home, KeyModifiers::CONTROL));
        let first = screen(&app, 80, 24);
        assert!(first.contains("LOG00000"));
        assert!(!first.contains("LOG69999"));
        app.handle_key(KeyEvent::new(KeyCode::End, KeyModifiers::CONTROL));
        assert!(screen(&app, 80, 24).contains("LOG69999"));
    }

    #[test]
    fn sidebar_prioritizes_reported_tokens_and_cache_hit_rate() {
        let mut app = App::new(
            WorkspaceView::default(),
            vec![],
            None,
            Some(RunView {
                status: "COMPLETED".into(),
                usage: Some(super::super::api::UsageView {
                    prompt: 3000,
                    completion: 500,
                    total: 3500,
                    cached_input: 1000,
                    complete: true,
                    ..Default::default()
                }),
                ..Default::default()
            }),
            false,
        );
        app.details_open = true;
        let shown = screen(&app, 140, 36);
        for label in [
            "RUN USAGE",
            "Input",
            "Output",
            "Cache read",
            "Hit rate",
            "3,000",
            "3,500",
            "1,000",
            "33.3%",
            "Usage reported",
        ] {
            assert!(shown.contains(label), "missing {label}");
        }
        assert!(!shown.contains("Conversation context"));
        assert!(!shown.contains("interact..."));
    }

    #[test]
    fn sidebar_separates_current_task_model_from_the_next_selection() {
        super::super::i18n::set(super::super::i18n::Language::English);
        let mut app = App::new(
            WorkspaceView::default(),
            vec![],
            None,
            Some(RunView {
                status: "RUNNING".into(),
                actual_model: Some("active-model".into()),
                actual_provider: Some("active-provider".into()),
                execution_spec: serde_json::json!({"text":{"model":"active-model","reasoningEffort":"low"}}),
                usage: Some(super::super::api::UsageView {
                    prompt: 3000,
                    completion: 500,
                    total: 3500,
                    cached_input: 1000,
                    complete: true,
                    ..Default::default()
                }),
                ..Default::default()
            }),
            false,
        );
        app.selected_model = Some("next-model".into());
        app.selected_provider = Some("next-provider".into());
        app.selected_effort = "high".into();
        app.details_open = true;
        let shown = screen(&app, 140, 36);
        assert!(shown.contains("THIS TASK MODEL"));
        assert!(shown.contains("Next task model: next-model | effort: high"));
        assert!(shown.contains("active-model") && shown.contains("active-provider"));
        let rail = shown
            .lines()
            .filter_map(|line| line.split_once('│').map(|(_, right)| right))
            .collect::<Vec<_>>();
        assert!(rail
            .iter()
            .any(|row| row.contains("Reasoning effort") && row.contains("low")));
        assert!(!rail
            .iter()
            .any(|row| row.contains("next-model") || row.contains("next-provider")));
        let ends = ["3,500", "3,000", "1,000", "33.3%"].map(|number| {
            rail.iter()
                .find_map(|row| row.find(number).map(|start| start + number.len()))
                .expect("visible metric")
        });
        assert!(
            ends.iter().all(|end| *end == ends[0]),
            "numeric values must align: {ends:?}"
        );
        let compact = screen(&app, 112, 18);
        for metric in ["3,500", "3,000", "1,000", "33.3%"] {
            assert!(compact.contains(metric), "{compact}");
        }
        app.run.as_mut().unwrap().actual_model = Some("X".repeat(120));
        let long_model = screen(&app, 112, 28);
        assert!(long_model.contains("XXXXXXXXXXXXXXXXXXXXXXXXX..."));
        assert!(!long_model.contains("......"));
        assert!(long_model.contains("33.3%"));
        app.run.as_mut().unwrap().actual_model = None;
        assert_eq!(app.actual_model(), "active-model");
        app.run.as_mut().unwrap().execution_spec = serde_json::json!({});
        assert_eq!(app.actual_model(), "Awaiting model");
        app.run = None;
        let idle = screen(&app, 140, 36);
        assert!(idle.contains("NEXT TASK CONFIG"));
        assert!(idle.contains("Usage appears after a task"));
        assert!(!idle.contains("THIS TASK MODEL"));
    }

    #[test]
    fn terminal_layout_stays_full_width_when_resized() {
        for (width, height) in [
            (60, 18),
            (80, 24),
            (112, 28),
            (160, 40),
            (220, 50),
            (80, 18),
        ] {
            let mut app = App::new(
                WorkspaceView {
                    name: "Harnex".into(),
                    ..Default::default()
                },
                vec![],
                None,
                None,
                false,
            );
            app.sync_rail_width(width);
            app.timeline.push(TimelineItem {
                kind: TimelineKind::Assistant,
                title: "Harnex".into(),
                content: "layout content".into(),
                purpose: None,
                tool_input: None,
                queued: false,
                sequence: 0,
                content_complete: false,
                call_id: None,
                running: false,
                failed: false,
            });
            let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
            terminal
                .draw(|frame| draw(frame, &app, Theme::resolve("dark", false, false)))
                .unwrap();
            let buffer = terminal.backend().buffer();
            assert_eq!(
                buffer[(1, height - 4)].symbol(),
                "╭",
                "compact composer at {width}x{height}"
            );
            assert_eq!(
                buffer[(width - 3, height - 4)].symbol(),
                "╮",
                "full-width composer at {width}x{height}"
            );
            assert_eq!(buffer[(1, height - 2)].symbol(), "╰");
            assert_eq!(
                buffer[(4, 3)].symbol(),
                "H",
                "transcript must not become a centered reading column"
            );
            assert_eq!(buffer[(4, 4)].symbol(), "l");
            assert_eq!(app.details_open, width >= RAIL_MIN_WIDTH);
        }
    }

    #[test]
    fn long_approval_arguments_scroll_without_hiding_decisions() {
        use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
        let mut app = App::new(WorkspaceView::default(), vec![], None, None, false);
        let arguments = (0..50)
            .map(|n| format!("argument line {n}\n"))
            .collect::<String>()
            + "LAST_ARGUMENT";
        app.overlay = Some(Overlay::Interaction(InteractionState {
            scroll: 0,
            interaction: crate::tui::api::InteractionView {
                kind: "approval".into(),
                payload: serde_json::json!({"toolName":"shell", "arguments":arguments, "outsideWrite":true}),
                ..Default::default()
            },
            input: super::super::app::Composer::default(),
            rejecting: false,
        }));
        let first = screen(&app, 80, 24);
        assert!(first.contains("a allow once") && first.contains("d deny"));
        assert!(!first.contains("LAST_ARGUMENT"));
        for _ in 0..10 {
            assert!(app
                .handle_key(KeyEvent::new(KeyCode::PageDown, KeyModifiers::NONE))
                .is_empty());
        }
        let last = screen(&app, 80, 24);
        assert!(last.contains("LAST_ARGUMENT"));
        assert!(last.contains("a allow once") && last.contains("d deny"));
    }

    fn screen(app: &App, width: u16, height: u16) -> String {
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal
            .draw(|frame| draw(frame, app, Theme::named("dark", false)))
            .unwrap();
        terminal
            .backend()
            .buffer()
            .content
            .chunks(width as usize)
            .map(|row| {
                let mut covered = 0;
                row.iter()
                    .filter_map(|cell| {
                        if covered > 0 {
                            covered -= 1;
                            return None;
                        }
                        covered = UnicodeWidthStr::width(cell.symbol()).saturating_sub(1);
                        Some(cell.symbol())
                    })
                    .collect::<String>()
            })
            .collect::<Vec<_>>()
            .join("\n")
    }

    #[test]
    fn language_switch_updates_chrome_and_preserves_user_text_at_both_widths() {
        use crate::tui::i18n::{set, Language};
        for width in [80, 140] {
            let mut app = App::new(
                WorkspaceView {
                    name: "My project".into(),
                    root: "D:/项目".into(),
                    ..Default::default()
                },
                vec![],
                None,
                None,
                false,
            );
            app.composer.text = "English draft 中文草稿".into();
            app.sync_rail_width(width);
            set(Language::Chinese);
            let chinese = screen(&app, width, 24);
            assert!(chinese.contains("项目 Agent"), "{chinese}");
            assert!(chinese.contains("就绪"), "{chinese}");
            assert!(chinese.contains("English draft 中文草稿"));
            app.overlay = Some(Overlay::Picker(PickerState {
                kind: super::super::app::PickerKind::Commands,
                query: "".into(),
                selected: 5,
            }));
            let palette = screen(&app, width, 24);
            assert!(
                palette.contains("快捷键帮助"),
                "root commands should remain visible: {palette}"
            );
            assert!(palette.contains("命令"));
            set(Language::English);
            let english = screen(&app, width, 24);
            assert!(english.contains("Keyboard help"), "{english}");
            assert!(english.contains("Commands"));
            app.overlay = None;
            assert!(screen(&app, width, 24).contains("English draft 中文草稿"));
        }
    }

    #[test]
    fn chinese_approval_keeps_actions_visible_and_tool_arguments_unchanged() {
        use crate::tui::i18n::{set, Language};
        set(Language::Chinese);
        let mut app = App::new(WorkspaceView::default(), vec![], None, None, false);
        app.overlay = Some(Overlay::Interaction(InteractionState {
            scroll: 0,
            interaction: crate::tui::api::InteractionView {
                kind: "approval".into(),
                payload: serde_json::json!({"toolName":"shell", "arguments":"echo Hello", "outsideWrite":true}),
                ..Default::default()
            },
            input: super::super::app::Composer::default(),
            rejecting: false,
        }));
        let rendered = screen(&app, 80, 24);
        assert!(rendered.contains("需要批准"));
        assert!(rendered.contains("a 允许一次"));
        assert!(rendered.contains("d 拒绝"));
        assert!(rendered.contains("echo Hello"));
        set(Language::English);
    }

    #[test]
    fn home_and_notifications_keep_f1_visible_and_help_preserves_draft() {
        for language in [
            super::super::i18n::Language::English,
            super::super::i18n::Language::Chinese,
        ] {
            super::super::i18n::set(language);
            for width in [60, 80, 140] {
                let mut app = App::new(WorkspaceView::default(), vec![], None, None, false);
                let home = screen(&app, width, 24);
                assert!(
                    home.contains(tr("Ctrl+P command menu | F1 shortcuts")),
                    "{home}"
                );
                assert!(
                    home.contains(tr("History | Models & effort | Settings")),
                    "{home}"
                );
                assert!(
                    home.contains(tr("F2 model | F3 effort | Ctrl+N new")),
                    "{home}"
                );
                assert!(home.contains("Shift+Enter"));
                app.paste("a large 中文 draft\nsecond line");
                app.push_notice("A temporary notice");
                assert!(screen(&app, width, 24).contains(tr("F1 shortcuts")));
                app.handle_key(crossterm::event::KeyEvent::new(
                    crossterm::event::KeyCode::F(1),
                    crossterm::event::KeyModifiers::NONE,
                ));
                let first = screen(&app, width, 24);
                assert!(first.contains("Ctrl+X → Q"));
                app.handle_key(crossterm::event::KeyEvent::new(
                    crossterm::event::KeyCode::End,
                    crossterm::event::KeyModifiers::NONE,
                ));
                assert!(screen(&app, width, 24).contains("? (empty draft)"));
                app.handle_key(crossterm::event::KeyEvent::new(
                    crossterm::event::KeyCode::F(1),
                    crossterm::event::KeyModifiers::NONE,
                ));
                assert_eq!(app.composer.text, "a large 中文 draft\nsecond line");
            }
        }
    }

    #[test]
    fn composer_cursor_counts_cjk_columns_and_wraps() {
        assert_eq!(composer_cursor("你好a", "你好a".len(), 5), (0, 5));
        assert_eq!(composer_cursor("你好ab", "你好ab".len(), 5), (1, 1));
    }

    #[test]
    fn truncation_uses_display_width() {
        assert_eq!(truncate("你好世界", 5), "你...");
    }

    #[test]
    fn named_themes_keep_semantic_colors_distinct() {
        for name in ["auto", "dark", "light", "ansi"] {
            let theme = Theme::named(name, false);
            assert_ne!(theme.success, theme.danger);
            assert_ne!(theme.accent, theme.danger);
        }
        let plain = Theme::named("dark", true);
        assert_eq!(plain.accent, Color::Reset);
    }

    #[test]
    fn live_git_branch_is_muted_and_visible_at_wide_and_narrow_widths() {
        for width in [48, 80, 100, 140] {
            let theme = Theme::named("dark", false);
            let mut app = App::new(
                WorkspaceView {
                    name: "Local | Harnex".into(),
                    ..Default::default()
                },
                Vec::new(),
                None,
                None,
                false,
            );
            app.workspace_git = super::super::api::WorkspaceGitView {
                state: "branch".into(),
                branch: Some("feature/login".into()),
                commit: None,
            };
            let mut terminal = Terminal::new(TestBackend::new(width, 24)).unwrap();
            terminal.draw(|frame| draw(frame, &app, theme)).unwrap();
            let header = terminal
                .backend()
                .buffer()
                .content()
                .iter()
                .take(width as usize * 2)
                .map(|cell| cell.symbol())
                .collect::<String>();
            assert!(header.contains("git: feature/"), "{width}: {header}");
            let git_cell = terminal
                .backend()
                .buffer()
                .content()
                .iter()
                .take(width as usize * 2)
                .find(|cell| cell.symbol() == "g")
                .unwrap();
            assert_eq!(git_cell.fg, theme.muted);
            app.workspace_git = super::super::api::WorkspaceGitView {
                state: "detached".into(),
                branch: None,
                commit: Some("a1b2c3d4".into()),
            };
            terminal.draw(|frame| draw(frame, &app, theme)).unwrap();
            let header = terminal
                .backend()
                .buffer()
                .content()
                .iter()
                .take(width as usize * 2)
                .map(|cell| cell.symbol())
                .collect::<String>();
            assert!(header.contains("git: HEAD"));
            app.agent_available = false;
            terminal.draw(|frame| draw(frame, &app, theme)).unwrap();
            let header = terminal
                .backend()
                .buffer()
                .content()
                .iter()
                .take(width as usize * 2)
                .map(|cell| cell.symbol())
                .collect::<String>();
            assert!(!header.contains("git:"));
        }
    }

    #[test]
    fn personal_welcome_does_not_require_a_project_or_advertise_agent_commands() {
        let mut app = App::new(WorkspaceView::default(), Vec::new(), None, None, false);
        app.agent_available = false;
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();
        terminal
            .draw(|frame| draw(frame, &app, Theme::named("ansi", true)))
            .unwrap();
        let screen = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>();
        assert!(screen.contains("Personal conversations"));
        assert!(screen.contains("Ask a question"));
        assert!(!screen.contains("coding agent") && !screen.contains("Ready in your workspace"));
    }

    #[test]
    fn wide_and_narrow_layouts_keep_primary_actions_visible() {
        for (width, height) in [(80, 24), (RAIL_MIN_WIDTH, 30), (140, 36)] {
            let mut app = App::new(
                WorkspaceView {
                    name: "Harnex".to_string(),
                    root: "D:/code/myproject/Harnex".to_string(),
                    ..WorkspaceView::default()
                },
                Vec::new(),
                None,
                Some(RunView {
                    run_id: "run-1".to_string(),
                    thread_id: "thread-1".to_string(),
                    status: "RUNNING".to_string(),
                    actual_model: Some("gpt-5.6-terra".to_string()),
                    ..RunView::default()
                }),
                false,
            );
            app.thread_title = "TUI 视觉与交互实现".to_string();
            app.details_open = true;
            app.timeline = vec![
                TimelineItem {
                    kind: TimelineKind::User,
                    title: "You".to_string(),
                    content: "实现高质量 native TUI".to_string(),
                    purpose: None,
                    tool_input: None,
                    queued: false,
                    sequence: 0,
                    content_complete: false,
                    call_id: None,
                    running: false,
                    failed: false,
                },
                TimelineItem {
                    kind: TimelineKind::Assistant,
                    title: "Harnex".to_string(),
                    content: "先完成单向状态流，再建立视觉验收。".to_string(),
                    purpose: None,
                    tool_input: None,
                    queued: false,
                    sequence: 0,
                    content_complete: false,
                    call_id: None,
                    running: true,
                    failed: false,
                },
                TimelineItem {
                    kind: TimelineKind::Thinking,
                    title: "Thinking".to_string(),
                    content: "private reasoning should stay collapsed".to_string(),
                    purpose: None,
                    tool_input: None,
                    queued: false,
                    sequence: 0,
                    content_complete: false,
                    call_id: None,
                    running: true,
                    failed: false,
                },
                TimelineItem {
                    kind: TimelineKind::Tool,
                    title: "cargo test".to_string(),
                    content: "11 tests".to_string(),
                    purpose: None,
                    tool_input: None,
                    queued: false,
                    sequence: 0,
                    content_complete: false,
                    call_id: Some("call-1".to_string()),
                    running: false,
                    failed: false,
                },
            ];
            let backend = TestBackend::new(width, height);
            let mut terminal = Terminal::new(backend).unwrap();
            terminal
                .draw(|frame| draw(frame, &app, Theme::named("auto", true)))
                .unwrap();
            let screen = terminal
                .backend()
                .buffer()
                .content()
                .chunks(width as usize)
                .map(|row| row.iter().map(|cell| cell.symbol()).collect::<String>())
                .collect::<Vec<_>>()
                .join("\n");
            if std::env::var_os("HARNEX_TUI_PRINT_SNAPSHOT").is_some() {
                eprintln!("\n--- TUI {width}x{height} ---\n{screen}\n");
            }
            assert!(screen.contains("harnex"));
            assert!(screen.contains("│ You"));
            assert!(screen.contains("Harnex"));
            assert!(screen.contains("Thinking collapsed"));
            assert!(!screen.contains("private reasoning should stay collapsed"));
            assert!(screen.contains("native TUI"));
            assert!(screen.contains("Esc stop"));
            assert!(screen.contains("message") || screen.contains("update current task"));
            if width >= RAIL_MIN_WIDTH {
                assert!(screen.contains("CURRENT TASK"));
            }
        }
    }

    #[test]
    fn approval_overlay_keeps_decisions_visible_at_80x24() {
        let mut app = App::new(
            WorkspaceView {
                name: "Harnex".to_string(),
                root: "D:/code/Harnex".to_string(),
                ..WorkspaceView::default()
            },
            Vec::new(),
            None,
            None,
            false,
        );
        app.overlay = Some(Overlay::Interaction(InteractionState {
            scroll: 0,
            interaction: crate::tui::api::InteractionView {
                kind: "approval".to_string(),
                thread_id: "thread-1".to_string(),
                call_id: "call-1".to_string(),
                payload: serde_json::json!({
                    "toolName": "file_edit",
                    "arguments": "backend/src/main/java/com/harnex/UploadService.java"
                }),
                ..crate::tui::api::InteractionView::default()
            },
            input: crate::tui::app::Composer::default(),
            rejecting: false,
        }));
        let backend = TestBackend::new(80, 24);
        let mut terminal = Terminal::new(backend).unwrap();
        terminal
            .draw(|frame| draw(frame, &app, Theme::named("auto", true)))
            .unwrap();
        let screen = terminal
            .backend()
            .buffer()
            .content()
            .chunks(80)
            .map(|row| row.iter().map(|cell| cell.symbol()).collect::<String>())
            .collect::<Vec<_>>()
            .join("\n");
        assert!(screen.contains("Approval required"));
        assert!(screen.contains("file_edit"));
        assert!(screen.contains("allow once"));
        assert!(screen.contains("deny"));
    }

    #[test]
    fn streaming_redraw_clears_expanded_thinking_from_main_and_rail() {
        let mut app = App::new(
            WorkspaceView {
                name: "Harnex".to_string(),
                root: "D:/code/Harnex".to_string(),
                ..WorkspaceView::default()
            },
            Vec::new(),
            None,
            Some(RunView {
                run_id: "run-1".to_string(),
                thread_id: "thread-1".to_string(),
                status: "RUNNING".to_string(),
                ..RunView::default()
            }),
            false,
        );
        app.details_open = true;
        app.show_thinking = true;
        app.apply_stream(StreamEvent {
            kind: "thinking_start".to_string(),
            run_id: Some("run-1".to_string()),
            thread_id: Some("thread-1".to_string()),
            seq: 1,
            stream_epoch: Some(1),
            data: serde_json::json!({}),
            ..StreamEvent::default()
        });
        app.apply_stream(StreamEvent {
            kind: "thinking_delta".to_string(),
            run_id: Some("run-1".to_string()),
            thread_id: Some("thread-1".to_string()),
            seq: 2,
            stream_epoch: Some(1),
            data: serde_json::json!({"text":"SHOULD_NOT_LEAK ".repeat(200)}),
            ..StreamEvent::default()
        });
        let backend = TestBackend::new(140, 36);
        let mut terminal = Terminal::new(backend).unwrap();
        terminal
            .draw(|frame| draw(frame, &app, Theme::named("ansi", false)))
            .unwrap();

        app.show_thinking = false;
        app.apply_stream(StreamEvent {
            kind: "text_delta".to_string(),
            run_id: Some("run-1".to_string()),
            thread_id: Some("thread-1".to_string()),
            seq: 3,
            stream_epoch: Some(1),
            data: serde_json::json!({"text":"clean assistant output"}),
            ..StreamEvent::default()
        });
        terminal
            .draw(|frame| draw(frame, &app, Theme::named("ansi", false)))
            .unwrap();
        let screen = terminal
            .backend()
            .buffer()
            .content()
            .chunks(140)
            .map(|row| row.iter().map(|cell| cell.symbol()).collect::<String>())
            .collect::<Vec<_>>()
            .join("\n");
        assert!(!screen.contains("SHOULD_NOT_LEAK"));
        assert!(screen.contains("Thinking collapsed"));
        assert!(screen.contains("clean assistant output"));
        assert!(screen.contains("RUN"));
    }
}
