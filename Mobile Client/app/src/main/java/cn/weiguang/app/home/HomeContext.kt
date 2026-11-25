package cn.weiguang.app.home

import org.json.JSONObject

data class HomeCity(val code: String, val name: String) {
    companion object {
        fun fromJson(obj: JSONObject) = HomeCity(obj.getString("code"), obj.getString("name"))
    }
}

data class HomePlace(val id: String, val name: String, val address: String) {
    companion object {
        fun fromJson(obj: JSONObject) =
            HomePlace(obj.getString("placeId"), obj.getString("name"), obj.optString("address"))
    }
}

data class HomePlaceResults(val status: String, val places: List<HomePlace>) {
    companion object {
        fun fromJson(obj: JSONObject): HomePlaceResults {
            val rows = obj.optJSONArray("items")
            return HomePlaceResults(obj.optString("status"), List(rows?.length() ?: 0) {
                HomePlace.fromJson(requireNotNull(rows).getJSONObject(it))
            })
        }
    }
}

data class HomeWeather(
    val status: String,
    val condition: String,
    val temperatureC: Double?,
    val reportedAt: String?
) {
    fun display(): String? {
        val temperature = temperatureC ?: return null
        if (status != "available" || condition.isBlank()) return null
        val label = if (temperature % 1 == 0.0) temperature.toInt().toString() else temperature.toString()
        return "$condition ${label}°C"
    }
}

data class HomeCommute(
    val from: String,
    val to: String,
    val status: String,
    val routeMinutes: Int?,
    val remainingMinutes: Int?,
    val bufferMinutes: Int?
)

data class HomeLocalContext(
    val cities: List<HomeCity>,
    val city: HomeCity?,
    val weather: HomeWeather,
    val places: Map<String, HomePlace>,
    val commutes: List<HomeCommute>
) {
    companion object {
        fun fromJson(obj: JSONObject): HomeLocalContext {
            val cities = obj.getJSONArray("cityOptions")
            val weather = obj.getJSONObject("weather")
            val places = obj.getJSONObject("places")
            val commutes = obj.getJSONArray("commutes")
            return HomeLocalContext(
                cities = List(cities.length()) { HomeCity.fromJson(cities.getJSONObject(it)) },
                city = obj.optJSONObject("city")?.let(HomeCity::fromJson),
                weather = HomeWeather(weather.optString("status"), weather.optString("condition"),
                    weather.optDouble("temperatureC").takeIf { it.isFinite() },
                    weather.optString("reportedAt").takeUnless { it.isBlank() || weather.isNull("reportedAt") }),
                places = places.keys().asSequence().associateWith { HomePlace.fromJson(places.getJSONObject(it)) },
                commutes = List(commutes.length()) {
                    val row = commutes.getJSONObject(it)
                    HomeCommute(row.getString("fromEventId"), row.getString("toEventId"), row.optString("status"),
                        row.readInt("routeMinutes"), row.readInt("remainingMinutes"), row.readInt("bufferMinutes"))
                }
            )
        }

        private fun JSONObject.readInt(key: String): Int? = if (isNull(key)) null else getInt(key)
    }
}
