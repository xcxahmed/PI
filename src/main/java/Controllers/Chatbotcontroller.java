package Controllers;

import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.scene.CacheHint;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

public class Chatbotcontroller {

    @FXML private WebView webView;

    private WebEngine engine;
    private Chatbotbridge bridge;

    @FXML
    public void initialize() {
        webView.setCache(true);
        webView.setCacheHint(CacheHint.SPEED);
        engine = webView.getEngine();
        engine.setOnAlert(e -> System.out.println("[ChatbotJS] " + e.getData()));

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                try {
                    bridge = new Chatbotbridge(engine);
                    bridge.inject();
                    System.out.println("✅ ChatbotController prêt");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        java.net.URL url = getClass().getResource("/html/chatbot.html");
        if (url == null) {
            System.err.println("❌ chatbot.html introuvable");
            return;
        }
        engine.load(url.toExternalForm());
    }
}