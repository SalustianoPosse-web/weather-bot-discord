import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import okhttp3.*;
import com.google.gson.*;
import java.io.IOException;

public class WeatherBotDiscord extends ListenerAdapter {

    // ✅ Leer tokens desde variables de entorno
    private static final String DISCORD_TOKEN = System.getenv("DISCORD_TOKEN");
    private static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");
    private static final String WEATHER_API_KEY = System.getenv("OPENWEATHER_API_KEY");

    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String WEATHER_API_URL = "https://api.openweathermap.org/data/2.5/weather";

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Gson gson = new Gson();

    public static void main(String[] args) {
        // Validar que las variables de entorno existan
        if (DISCORD_TOKEN == null || GROQ_API_KEY == null || WEATHER_API_KEY == null) {
            System.err.println("❌ ERROR: Faltan variables de entorno!");
            System.err.println("Asegúrate de configurar:");
            System.err.println("  - DISCORD_TOKEN");
            System.err.println("  - GROQ_API_KEY");
            System.err.println("  - OPENWEATHER_API_KEY");
            System.exit(1);
        }

        try {
            JDABuilder builder = JDABuilder.createDefault(DISCORD_TOKEN);
            builder.enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES);
            builder.addEventListeners(new WeatherBotDiscord());
            builder.build();

            System.out.println("✅ Bot iniciado correctamente!");
        } catch (Exception e) {
            System.err.println("❌ Error al iniciar el bot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignorar mensajes de bots
        if (event.getAuthor().isBot()) return;

        Message message = event.getMessage();
        String content = message.getContentRaw();

        // Responder a mensajes que empiecen con !clima
        if (content.startsWith("!clima")) {

            // Mostrar que está escribiendo
            event.getChannel().sendTyping().queue();

            // Procesar en un hilo separado para no bloquear
            new Thread(() -> {
                try {
                    String pregunta = content.replace("!clima", "").trim();
                    String respuesta = procesarPreguntaClima(pregunta);
                    event.getChannel().sendMessage(respuesta).queue();
                } catch (Exception e) {
                    event.getChannel().sendMessage("❌ Lo siento, ocurrió un error: " + e.getMessage()).queue();
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private String procesarPreguntaClima(String pregunta) throws IOException {
        // Paso 1: Usar Groq AI para extraer la ciudad de la pregunta
        String ciudad = extraerCiudadConGroq(pregunta);

        if (ciudad == null || ciudad.isEmpty()) {
            return "❓ No pude identificar la ciudad. Por favor, pregunta algo como: '¿Cómo está el clima en Buenos Aires?'";
        }

        // Paso 2: Obtener datos del clima desde la API
        JsonObject datosClima = obtenerDatosClima(ciudad);

        if (datosClima == null) {
            return "❌ No pude encontrar información del clima para: " + ciudad;
        }

        // Paso 3: Usar Groq AI para generar una respuesta natural
        return generarRespuestaConGroq(pregunta, ciudad, datosClima);
    }

    private String extraerCiudadConGroq(String pregunta) throws IOException {
        String prompt = "Extrae SOLAMENTE el nombre de la ciudad de esta pregunta sobre clima. " +
                "Si no hay ciudad, responde 'NINGUNA'. " +
                "Pregunta: " + pregunta + "\n" +
                "Ciudad:";

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "llama-3.3-70b-versatile");

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);

        requestBody.add("messages", messages);
        requestBody.addProperty("temperature", 0.3);
        requestBody.addProperty("max_tokens", 50);

        Request request = new Request.Builder()
                .url(GROQ_API_URL)
                .addHeader("Authorization", "Bearer " + GROQ_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                String ciudad = json.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString().trim();

                return ciudad.equals("NINGUNA") ? null : ciudad;
            }
        }
        return null;
    }

    private JsonObject obtenerDatosClima(String ciudad) throws IOException {
        HttpUrl url = HttpUrl.parse(WEATHER_API_URL).newBuilder()
                .addQueryParameter("q", ciudad)
                .addQueryParameter("appid", WEATHER_API_KEY)
                .addQueryParameter("units", "metric")
                .addQueryParameter("lang", "es")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return gson.fromJson(response.body().string(), JsonObject.class);
            }
        }
        return null;
    }

    private String generarRespuestaConGroq(String preguntaOriginal, String ciudad, JsonObject datosClima) throws IOException {
        // Extraer información del clima
        double temperatura = datosClima.getAsJsonObject("main").get("temp").getAsDouble();
        double sensacion = datosClima.getAsJsonObject("main").get("feels_like").getAsDouble();
        int humedad = datosClima.getAsJsonObject("main").get("humidity").getAsInt();
        String descripcion = datosClima.getAsJsonArray("weather")
                .get(0).getAsJsonObject()
                .get("description").getAsString();
        double viento = datosClima.getAsJsonObject("wind").get("speed").getAsDouble();

        String datosClimaTexto = String.format(
                "Ciudad: %s\n" +
                        "Temperatura: %.1f°C\n" +
                        "Sensación térmica: %.1f°C\n" +
                        "Descripción: %s\n" +
                        "Humedad: %d%%\n" +
                        "Viento: %.1f m/s",
                ciudad, temperatura, sensacion, descripcion, humedad, viento
        );

        String prompt = "Eres un asistente meteorológico amigable. Responde a la pregunta del usuario de forma natural y conversacional.\n\n" +
                "Pregunta: " + preguntaOriginal + "\n\n" +
                "Datos del clima:\n" + datosClimaTexto + "\n\n" +
                "Respuesta (usa emojis apropiados):";

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", "llama-3.3-70b-versatile");

        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        messages.add(message);

        requestBody.add("messages", messages);
        requestBody.addProperty("temperature", 0.7);
        requestBody.addProperty("max_tokens", 300);

        Request request = new Request.Builder()
                .url(GROQ_API_URL)
                .addHeader("Authorization", "Bearer " + GROQ_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
                return json.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();
            }
        }
        return "❌ Error al generar la respuesta";
    }
}