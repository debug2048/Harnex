package com.harnex.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolCall {
    private String id;
    private String name;
    /** Raw JSON arguments from the model. */
    private String arguments;
}
