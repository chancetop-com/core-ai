package ai.core.agent.function;

import ai.core.api.tool.function.CoreAiMethod;
import ai.core.api.tool.function.CoreAiParameter;
import core.framework.web.exception.BadRequestException;

import java.util.Map;

/**
 * Test service for weather-related function calls
 *
 * @author stephen
 */
public class WeatherService {
    public Map<String, Double> weathers = Map.of("shanghai", 30d, "beijing", 28d, "xiamen", 25d);
    public Map<String, String> airQualities = Map.of("shanghai", "Good", "beijing", "Bad", "xiamen", "Great");

    @CoreAiMethod(name = "getTemperature", description = "get temperature of city: beijing, shanghai, xiamen.")
    public String getTemperature(@CoreAiParameter(
            name = "city",
            description = "the city that you want to get the temperature",
            required = true,
            enums = {"beijing", "shanghai", "xiamen"}) String city) {
        if (!weathers.containsKey(city)) throw new BadRequestException("CITY NOT SUPPORTED");
        return String.valueOf(weathers.get(city));
    }

    @CoreAiMethod(name = "getAirQuality", description = "get air quality of city: beijing, shanghai, xiamen.")
    public String getAirQuality(@CoreAiParameter(
            name = "city",
            description = "the city that you want to get the air quality",
            required = true,
            enums = {"beijing", "shanghai", "xiamen"}) String city) {
        if (!airQualities.containsKey(city)) throw new BadRequestException("CITY NOT SUPPORTED");
        return airQualities.get(city);
    }
}
