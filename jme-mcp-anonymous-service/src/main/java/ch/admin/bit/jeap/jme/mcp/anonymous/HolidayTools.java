package ch.admin.bit.jeap.jme.mcp.anonymous;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Two tools side by side: this service default-denies like any other, but with no jEAP OAuth2 resource server
 * configured at all, there is no way to ever be anything but anonymous. {@code is_public_holiday} declares
 * {@code permitAll()} and so stays reachable; {@code whoami} declares {@code hasRole('READER')} and is
 * permanently unreachable as a result, not a bug.
 */
@Component
public class HolidayTools {

    private static final Set<String> PUBLIC_HOLIDAYS_2026 = Set.of("2026-01-01", "2026-08-01", "2026-12-25");

    @McpTool(name = "is_public_holiday", description = "Checks if a date (yyyy-MM-dd) is a public holiday. Open to anonymous callers.")
    @PreAuthorize("permitAll()")
    public boolean isPublicHoliday(@McpToolParam(description = "Date as yyyy-MM-dd", required = true) String date) {
        return PUBLIC_HOLIDAYS_2026.contains(date);
    }

    @McpTool(name = "whoami", description = "Returns the caller's authenticated name. Requires the READER role, so always FORBIDDEN here.")
    @PreAuthorize("hasRole('READER')")
    public String whoami() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }
}
