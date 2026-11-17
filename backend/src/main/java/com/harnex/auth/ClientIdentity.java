package com.harnex.auth;

import com.harnex.run.RunClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/** Client attribution inside an authenticated Runtime; it does not grant tool or filesystem permissions. */
public final class ClientIdentity {
    public static final String HEADER = "X-Harnex-Client-Id";
    private ClientIdentity() {}

    public static String current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request)) return null;
        String value = request.getRequest().getHeader(HEADER);
        if (value == null || value.isBlank()) return "anonymous";
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,95}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid client identity");
        }
        return value;
    }

    /** Resolve at the HTTP entry point before handing work to application/domain services. */
    public static RunClient actor() {
        String id = current();
        return id == null ? RunClient.INTERNAL : new RunClient(id);
    }
}
