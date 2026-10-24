import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

//Immutable data for "val"

data class WeatherInfo(
    val temperature: Double,
    val cloudCoverPercentage: Int, // 0-100%
    val sourceName: String
)

data class AuroraForecast(
    val kpIndex: Double, // Index 0-9
    val stormProbability: Int, //probability %
    val sourceName: String
)

// Combined result of our check
data class HunterReport(
    val weather: WeatherInfo,
    val aurora: AuroraForecast,
)

data class Coordinates(
    val latitude: Double,
    val longitude: Double,
    val cityName: String
)

// GEOLOCATION SERVISE
// Automatically derected user location using IP address
object GeoLocationService {
    fun fetchCurrentLocation(): Coordinates {
        val url = "http://ip-api.com/json"

        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()

        // Parsing city and coordinates using Regex
        val latRegex = "\"lat\":\\s*([-\\d.]+)".toRegex()
        val lonRegex = "\"lon\":\\s*([-\\d.]+)".toRegex()
        val cityRegex = "\"city\":\\s*\"([^\"]+)\"".toRegex()

        val lat = latRegex.find(body)?.groupValues?.get(1)?.toDouble() ?: 69.6492 // Fallback to Tromsø
        val lon = lonRegex.find(body)?.groupValues?.get(1)?.toDouble() ?: 18.9553
        val city = cityRegex.find(body)?.groupValues?.get(1) ?: "Tromsø (Fallback)"

        return Coordinates(lat, lon, city)
    }
}

// --- WEATHER DATA SOURCE INTERFACE ---
interface WeatherDataSource {
    fun fetchWeather(coords: Coordinates): WeatherInfo
}

// --- IMPLEMENTATION: Open-Meteo API Source with Dynamic Coordinates ---
class OpenMeteoWeatherDataSource : WeatherDataSource {
    override fun fetchWeather(coords: Coordinates): WeatherInfo {
        // Constructing URL dynamically based on user's real coordinates
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${coords.latitude}&longitude=${coords.longitude}&current=temperature_2m,cloud_cover"

        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()

        // Parsing weather values using Regex
        val tempRegex = "\"temperature_2m\":\\s*([-\\d.]+)".toRegex()
        val cloudRegex = "\"cloud_cover\":\\s*(\\d+)".toRegex()

        val temp = tempRegex.find(body)?.groupValues?.get(1)?.toDouble() ?: 0.0
        val clouds = cloudRegex.find(body)?.groupValues?.get(1)?.toInt() ?: 50

        return WeatherInfo(
            temperature = temp,
            cloudCoverPercentage = clouds,
            sourceName = "Open-Meteo Live API (${coords.cityName})"
        )
    }
}
// --- AURORA DATA SOURCE ---
interface AuroraDataSource {
    fun fetchAurora(): AuroraForecast
}

// NOAA Space Weather Prediction Center Live Data Source
class NoaaAuroraDataSource : AuroraDataSource {
    override fun fetchAurora(): AuroraForecast {
        // Official NOAA endpoint for planetary K-index data matrix
        val url = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"

        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()

        // The response is a JSON array of arrays. We grab the very last entry (most recent reading).
        val rows = body.split("],[")
        if (rows.isNotEmpty()) {
            val lastRow = rows.last()
            val parts = lastRow.split(",")
            if (parts.size >= 2) {
                val kpStr = parts[1].replace("\"", "").trim()
                val kp = kpStr.toDoubleOrNull() ?: 2.0

                // Calculate rough storm probability based on Kp index (Kp 0-9 scale)
                val probability = ((kp / 9.0) * 100).toInt().coerceIn(0, 100)

                return AuroraForecast(
                    kpIndex = kp,
                    stormProbability = probability,
                    sourceName = "NOAA SWPC Live API"
                )
            }
        }

        // Fallback if parsing fails
        return AuroraForecast(kpIndex = 2.0, stormProbability = 20, sourceName = "NOAA Fallback")
    }
}
//EXTENTION FUNCTION
// Extension function for WeatherInfo to check if the sky is clear enough (< 30% clouds)
fun WeatherInfo.isSkyClear(): Boolean {
    return this.cloudCoverPercentage < 30
}

fun HunterReport.evaluateHunt(): String {
    return if (this.weather.isSkyClear() && this.aurora.kpIndex >= 4.0) {
        "GO HUNTING! Clear skies and high geomagnetic activity!"
    } else if (this.weather.isSkyClear()) {
        "Skies are clear, but Kp-index is too low. Wait for solar activity."
    } else {
        "Stay home. Too cloudy to see any Aurora."
    }
}

// MAIN FUNCTION

fun main() {
    println("Initializing Aurora & Cloud Hunter...")

    try {
        // Step 1: Automatically detect user's location via IP
        println("Detecting your location...")
        val userLocation = GeoLocationService.fetchCurrentLocation()
        println("Location: ${userLocation.cityName} (Lat: ${userLocation.latitude}, Lon: ${userLocation.longitude})")

        //Step 2: Fetch weather for the detected coordinates using Dependency Inversion
        val weatherSource: WeatherDataSource = OpenMeteoWeatherDataSource()
        val auroraSource: AuroraDataSource = NoaaAuroraDataSource()

        println("Fetching live weather and geomagnetic forecasts...")
        val liveWeather = weatherSource.fetchWeather(userLocation)
        val liveAurora = auroraSource.fetchAurora()

        val report = HunterReport(liveWeather, liveAurora)

        // Step 3: Print final report
        println("\n=== AURORA & CLOUD HUNTER REPORT (LIVE) ===")
        println("Weather Source: ${report.weather.sourceName}")
        println("Temperature: ${report.weather.temperature}°C")
        println("Cloud Cover: ${report.weather.cloudCoverPercentage}%")
        println("----------------------------------------")
        println("Aurora Source: ${report.aurora.sourceName}")
        println("Kp-Index: ${report.aurora.kpIndex}")
        println("Storm Probability: ${report.aurora.stormProbability}%")
        println("----------------------------------------")
        println("Verdict: ${report.evaluateHunt()}")
    } catch (e: Exception) {
        println("Failed to fetch live data: ${e.message}")
    }
}