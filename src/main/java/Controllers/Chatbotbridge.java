package Controllers;

import Entities.Evenement;
import Entities.Invitation;
import Services.EvenementService;
import Services.InvitationService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.web.WebEngine;
import javafx.stage.Stage;
import javafx.stage.Window;
import netscape.javascript.JSObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Bridge Java ↔ JS pour chatbot.html
 * Exposé sous window.chatBridge
 * Appelle l'API Anthropic Claude
 */
public class Chatbotbridge {

    private final EvenementService  evService  = new EvenementService();
    private final InvitationService invService = new InvitationService();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm");

    // ✅ Clé API Anthropic — à mettre dans config.properties
    private static final String API_KEY;
    static {
        java.util.Properties config = new java.util.Properties();
        try (InputStream in = Chatbotbridge.class.getResourceAsStream("/config.properties")) {
            if (in != null)
                config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("⚠️ config.properties introuvable");
        }
        API_KEY = config.getProperty("anthropic.api.key", "");
    }

    private final WebEngine engine;
    private JSObject jsWindow;

    public Chatbotbridge(WebEngine engine) {
        this.engine = engine;
    }

    public void inject() {
        jsWindow = (JSObject) engine.executeScript("window");
        jsWindow.setMember("chatBridge", this);
        System.out.println("✅ ChatbotBridge injecté");
    }

    // ══════════════════════════════════════════════
    //  POSER UNE QUESTION AU CHATBOT
    //  Appelé depuis JS : window.chatBridge.ask(question)
    // ══════════════════════════════════════════════
    public void ask(String question) {
        new Thread(() -> {
            try {
                String context = buildContext(); // Step 1: get DB data
                String systemPrompt = buildSystemPrompt(context);// Step 2: build instructions for Claude
                String reponse = callClaude(systemPrompt, question);// Step 3: call Claude API

                final String safeReponse = escapeForJs(reponse);
                Platform.runLater(() ->
                        engine.executeScript("receiveAnswer('" + safeReponse + "')")
                );

            } catch (Exception e) {
                e.printStackTrace();
                final String errMsg = escapeForJs("❌ Erreur : " + e.getMessage());
                Platform.runLater(() ->
                        engine.executeScript("receiveAnswer('" + errMsg + "')")
                );
            }
        }).start();
    }

    // ══════════════════════════════════════════════
    //  CONSTRUIRE LE CONTEXTE DEPUIS LA DB
    // ══════════════════════════════════════════════
    private String buildContext() throws Exception {
        List<Evenement>  evs  = evService.getAll();
        List<Invitation> invs = invService.getAll();

        StringBuilder sb = new StringBuilder();

        sb.append("=== ÉVÉNEMENTS (").append(evs.size()).append(") ===\n");
        for (Evenement ev : evs) {
            sb.append("- ID:").append(ev.getId())
                    .append(" | Titre: ").append(ev.getTitre())
                    .append(" | Mode: ").append(ev.getMode())
                    .append(" | Début: ").append(ev.getDateDebut() != null ? ev.getDateDebut().format(FMT) : "—")
                    .append(" | Fin: ").append(ev.getDateFin() != null ? ev.getDateFin().format(FMT) : "—");
            if ("PRESENTIEL".equals(ev.getMode()))
                sb.append(" | Lieu: ").append(ev.getLieu() != null ? ev.getLieu() : "—");
            else
                sb.append(" | Lien: ").append(ev.getMeetingLink() != null ? ev.getMeetingLink() : "—");
            sb.append("\n");
        }

        sb.append("\n=== INVITATIONS (").append(invs.size()).append(") ===\n");
        for (Invitation inv : invs) {
            sb.append("- ID:").append(inv.getId())
                    .append(" | EvenementID: ").append(inv.getEvenementId())
                    .append(" | Email: ").append(inv.getEmail())
                    .append(" | Rôle: ").append(inv.getRoleInvite() != null ? inv.getRoleInvite() : "—")
                    .append(" | Date invitation: ").append(inv.getDateInvitation() != null ? inv.getDateInvitation().format(FMT) : "—")
                    .append("\n");
        }

        return sb.toString();
    }

    // ══════════════════════════════════════════════
    //  SYSTEM PROMPT — Comportement du chatbot
    // ══════════════════════════════════════════════
    private String buildSystemPrompt(String context) {
        return "Tu es Alex, l'assistant virtuel d'Investia, une plateforme de gestion d'événements.\n\n"
                + "RÈGLES STRICTES :\n"
                + "1. Tu réponds UNIQUEMENT aux questions sur les événements et les invitations.\n"
                + "2. Si on te pose une question sur autre chose, réponds exactement : "
                + "'Je suis uniquement disponible pour répondre aux questions sur les événements et invitations Investia.'\n"
                + "3. Tu réponds toujours en français.\n"
                + "4. Tu es concis, professionnel et sympathique.\n"
                + "5. Tu utilises les données ci-dessous pour répondre — ces données sont en temps réel.\n\n"
                + "DONNÉES ACTUELLES DE LA BASE :\n"
                + context + "\n\n"
                + "Réponds de façon claire et structurée.";
    }

    // ══════════════════════════════════════════════
    //  APPEL API ANTHROPIC
    // ══════════════════════════════════════════════
    private String callClaude(String systemPrompt, String userMessage) throws Exception {

        // Mode hors ligne si pas de clé API
        if (API_KEY == null || API_KEY.isEmpty()) {
            System.out.println("⚠️ Pas de clé API — mode hors ligne");
            return repondreHorsLigne(userMessage);
        }

        URL url = new URL("https://api.anthropic.com/v1/messages");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type",      "application/json");
        conn.setRequestProperty("x-api-key",         API_KEY);
        conn.setRequestProperty("anthropic-version", "2023-06-01");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        String body = "{"
                + "\"model\":\"claude-haiku-4-5-20251001\","
                + "\"max_tokens\":500,"
                + "\"system\":" + toJsonString(systemPrompt) + ","
                + "\"messages\":[{"
                + "\"role\":\"user\","
                + "\"content\":" + toJsonString(userMessage)
                + "}]}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        System.out.println("API STATUS: " + status);

        InputStream is = (status == 200) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        String rawJson = response.toString();
        System.out.println("API RAW RESPONSE: " + rawJson);

        // ✅ Si erreur de crédit ou quota → basculer en mode hors ligne
        if (status != 200) {
            if (rawJson.contains("credit") || rawJson.contains("balance") || rawJson.contains("quota")) {
                System.out.println("⚠️ Crédits insuffisants — basculement en mode hors ligne");
                return repondreHorsLigne(userMessage);
            }
            // Autre erreur → afficher le message
            int msgIdx = rawJson.indexOf("\"message\":\"");
            if (msgIdx >= 0) {
                int start = msgIdx + 11;
                int end = rawJson.indexOf("\"", start);
                if (end > start) return "❌ Erreur API (" + status + ") : " + rawJson.substring(start, end);
            }
            return "❌ Erreur API HTTP " + status + " : " + rawJson;
        }

        return extractText(rawJson);
    }

    // ══════════════════════════════════════════════
    //  MODE HORS LIGNE (sans clé API ou crédits insuffisants)
    // ══════════════════════════════════════════════
    private String repondreHorsLigne(String question) throws Exception {
        List<Evenement>  evs  = evService.getAll();
        List<Invitation> invs = invService.getAll();
        String q = question.toLowerCase();

        // Combien d'événements ?
        if (q.contains("combien") && (q.contains("événement") || q.contains("evenement"))) {
            return "Il y a actuellement " + evs.size() + " événement(s) dans Investia.";
        }

        // Combien d'invitations ?
        if (q.contains("combien") && q.contains("invitation")) {
            return "Il y a actuellement " + invs.size() + " invitation(s) au total.";
        }

        // Liste des événements
        if (q.contains("liste") || q.contains("événements") || q.contains("evenements") || q.contains("quels")) {
            if (evs.isEmpty()) return "Aucun événement trouvé dans la base de données.";
            StringBuilder sb = new StringBuilder("Voici les événements disponibles :\n");
            for (Evenement ev : evs)
                sb.append("• ").append(ev.getTitre())
                        .append(" (").append(ev.getMode()).append(")")
                        .append(" — du ").append(ev.getDateDebut() != null ? ev.getDateDebut().format(FMT) : "?")
                        .append(" au ").append(ev.getDateFin() != null ? ev.getDateFin().format(FMT) : "?")
                        .append("\n");
            return sb.toString();
        }

        // Événements en ligne / présentiel
        long online = evs.stream().filter(e -> "EN_LIGNE".equals(e.getMode())).count();
        long pres   = evs.stream().filter(e -> "PRESENTIEL".equals(e.getMode())).count();
        if (q.contains("ligne") || q.contains("présentiel") || q.contains("presentiel") || q.contains("mode")) {
            return online + " événement(s) en ligne et " + pres + " événement(s) en présentiel.";
        }

        // Invitations pour un événement spécifique
        if (q.contains("invitation")) {
            if (invs.isEmpty()) return "Aucune invitation trouvée dans la base de données.";
            StringBuilder sb = new StringBuilder("Voici les invitations :\n");
            for (Invitation inv : invs)
                sb.append("• ").append(inv.getEmail())
                        .append(" — Événement ID: ").append(inv.getEvenementId())
                        .append(" (").append(inv.getRoleInvite() != null ? inv.getRoleInvite() : "—").append(")")
                        .append("\n");
            return sb.toString();
        }

        // Prochain événement
        if (q.contains("prochain") || q.contains("suivant")) {
            return evs.isEmpty()
                    ? "Aucun événement à venir."
                    : "Le prochain événement est : " + evs.get(0).getTitre()
                    + " le " + (evs.get(0).getDateDebut() != null ? evs.get(0).getDateDebut().format(FMT) : "date inconnue") + ".";
        }

        // Réponse par défaut
        return "Je peux vous renseigner sur les événements et invitations Investia.\n"
                + "Essayez : 'combien d'événements ?', 'liste des événements', 'événements en ligne', 'liste des invitations'.";
    }

    // ══════════════════════════════════════════════
    //  NAVIGATION
    // ══════════════════════════════════════════════
    public void goEvenements() {
        naviguer("/fxml/ajout_evenement.fxml", "Investia — Événements");
    }

    public void goInvitations() {
        naviguer("/fxml/invitation.fxml", "Investia — Invitations");
    }

    private void naviguer(String fxml, String titre) {
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(fxml));
                Parent root = loader.load();
                Stage stage = (Stage) Window.getWindows().stream()
                        .filter(Window::isShowing).findFirst().orElse(null);
                if (stage != null) {
                    stage.setTitle(titre);
                    stage.getScene().setRoot(root);
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }

    // ══════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════
    private String extractText(String json) {
        try {
            int idx = json.indexOf("\"text\":\"");
            if (idx < 0) return "❌ Réponse inattendue de l'API.";
            int start = idx + 8;

            StringBuilder sb = new StringBuilder();
            int i = start;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    if      (next == 'n')  { sb.append('\n'); i += 2; }
                    else if (next == 't')  { sb.append('\t'); i += 2; }
                    else if (next == '"')  { sb.append('"');  i += 2; }
                    else if (next == '\\') { sb.append('\\'); i += 2; }
                    else                   { sb.append(c);    i++; }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString().isEmpty() ? "❌ Réponse vide de l'API." : sb.toString();

        } catch (Exception e) {
            return "❌ Erreur lecture réponse : " + e.getMessage();
        }
    }

    private String toJsonString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t") + "\"";
    }

    private String escapeForJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\"", "\\\"");
    }
}