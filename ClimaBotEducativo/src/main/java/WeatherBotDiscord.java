import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import okhttp3.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/* Bot de Discord que responde preguntas sobre el clima usando IA */
public class WeatherBotDiscord extends ListenerAdapter {

    /* Tokens de APIs leídos desde variables de entorno (seguridad) */
    private static final String DISCORD_TOKEN = System.getenv("DISCORD_TOKEN");
    private static final String GROQ_API_KEY = System.getenv("GROQ_API_KEY");
    private static final String WEATHER_API_KEY = System.getenv("OPENWEATHER_API_KEY");

    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String WEATHER_API_URL = "https://api.openweathermap.org/data/2.5/weather";

    /* Cliente HTTP y parser JSON */
    private final OkHttpClient httpClient = new OkHttpClient();
    private final Gson gson = new Gson();

    /* MAIN: Inicia el bot y un servidor HTTP para Render */
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
            /* Configurar y arrancar el bot de Discord */
            JDABuilder builder = JDABuilder.createDefault(DISCORD_TOKEN);
            builder.enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES);
            builder.addEventListeners(new WeatherBotDiscord());
            builder.build();

            System.out.println("✅ Bot iniciado correctamente!");

            /* NUEVO: Iniciar servidor HTTP simple para que Render detecte un puerto */
            iniciarServidorWeb();

        } catch (Exception e) {
            System.err.println("❌ Error al iniciar el bot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /* Crea un servidor HTTP simple en el puerto 8080 para que Render lo detecte */
    private static void iniciarServidorWeb() throws IOException {
        int puerto = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);

        /* Ruta principal: muestra que el bot está funcionando */
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = "✅ Weather Bot está funcionando correctamente!\n" +
                        "🤖 Bot de Discord activo\n" +
                        "💬 Usa el comando !clima en Discord para probarlo";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        });

        /* Ruta de health check */
        server.createContext("/health", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = "{\"status\":\"healthy\",\"bot\":\"online\"}";
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("🌐 Servidor HTTP iniciado en puerto " + puerto);
    }

    /* Evento que se ejecuta automáticamente cuando alguien escribe en Discord */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Ignorar mensajes de bots
        if (event.getAuthor().isBot()) return;

        Message message = event.getMessage();
        String content = message.getContentRaw();

        // Responder a mensajes que empiecen con !clima
        if (content.startsWith("!clima")) {

            event.getChannel().sendTyping().queue();

            /* Procesar en un hilo separado para no bloquear el bot */
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

    /* Procesa la pregunta del usuario en 3 pasos: extraer ciudad, obtener clima, generar respuesta */
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

    /* Usa Groq AI (Llama 3.3) para extraer el nombre de la ciudad del texto del usuario */
    private String extraerCiudadConGroq(String pregunta) throws IOException {
        String prompt = "Extrae SOLAMENTE el nombre de la ciudad de esta pregunta sobre clima. " +
                "Si no hay ciudad, responde 'NINGUNA'. " +
                "Pregunta: " + pregunta + "\n" +
                "Ciudad:";

        /* Construir petición JSON para Groq API */
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

        /* Hacer petición HTTP POST */
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

    /* Obtiene datos reales del clima desde OpenWeatherMap API */
    private JsonObject obtenerDatosClima(String ciudad) throws IOException {
        /* Construir URL con parámetros: ciudad, unidades métricas, idioma español */
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

    /* Usa Groq AI para convertir datos técnicos en una respuesta natural y amigable */
    private String generarRespuestaConGroq(String preguntaOriginal, String ciudad, JsonObject datosClima) throws IOException {
        /* Extraer datos del JSON de OpenWeather */
        double temperatura = datosClima.getAsJsonObject("main").get("temp").getAsDouble();
        double sensacion = datosClima.getAsJsonObject("main").get("feels_like").getAsDouble();
        int humedad = datosClima.getAsJsonObject("main").get("humidity").getAsInt();
        String descripcion = datosClima.getAsJsonArray("weather")
                .get(0).getAsJsonObject()
                .get("description").getAsString();
        double viento = datosClima.getAsJsonObject("wind").get("speed").getAsDouble();

        /* Formatear datos en texto legible */
        String datosClimaTexto = String.format(
                "Ciudad: %s\n" +
                        "Temperatura: %.1f°C\n" +
                        "Sensación térmica: %.1f°C\n" +
                        "Descripción: %s\n" +
                        "Humedad: %d%%\n" +
                        "Viento: %.1f m/s",
                ciudad, temperatura, sensacion, descripcion, humedad, viento
        );

        /* Prompt para que la IA genere respuesta conversacional */
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