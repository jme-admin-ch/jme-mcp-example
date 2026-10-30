package ch.admin.bit.jeap.jme.mcp.anonymous;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Two more toy tools, identical in code to the ones in jme-mcp-authenticated-service, adding one more
 * public/permanently-unreachable pair next to HolidayTools.
 */
@Component
public class UtilityTools {

    @McpTool(name = "get_current_time", description = "Returns the current date and time for a time zone (default UTC). Open to anonymous callers.")
    @PreAuthorize("permitAll()")
    public String getCurrentTime(@McpToolParam(description = "IANA time zone id, e.g. Europe/Zurich", required = false) String zoneId) {
        ZoneId zone = (zoneId == null || zoneId.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(zoneId);
        return ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    @McpTool(name = "convert_temperature", description = "Converts a temperature between Celsius (C) and Fahrenheit (F). Requires the READER role, so always FORBIDDEN here.")
    @PreAuthorize("hasRole('READER')")
    public double convertTemperature(@McpToolParam(description = "Value to convert", required = true) double value,
                                      @McpToolParam(description = "Source unit, C or F", required = true) String fromUnit,
                                      @McpToolParam(description = "Target unit, C or F", required = true) String toUnit) {
        if (fromUnit.equalsIgnoreCase(toUnit)) {
            return value;
        }
        return "C".equalsIgnoreCase(fromUnit) ? value * 9 / 5 + 32 : (value - 32) * 5 / 9;
    }
}
