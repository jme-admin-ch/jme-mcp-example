package ch.admin.bit.jeap.jme.mcp.authenticated;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A single toy tool to show the starter's default-deny security: calling it requires a valid bearer token with
 * the READER role, see jme-mcp-oauth-mock for how to obtain one.
 */
@Component
public class WeatherTools {

    private static final Map<String, String> FORECASTS = Map.of(
            "bern", "Partly cloudy, 14°C",
            "zurich", "Light rain, 11°C",
            "geneva", "Sunny, 16°C"
    );

    @McpTool(name = "get_weather", description = "Returns a canned weather forecast for a city.")
    @PreAuthorize("hasRole('READER')")
    public String getWeather(@McpToolParam(description = "City name", required = true) String city) {
        return FORECASTS.getOrDefault(city.toLowerCase(), "No forecast available for " + city);
    }
}
