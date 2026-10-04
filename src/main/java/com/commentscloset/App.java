package com.commentscloset;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.Arrays;
import java.util.List;

public class App extends Application {
    private static final Db.Channel ALL = new Db.Channel(null, "Все каналы");

    private Db db;
    private Config config;
    private Task<Integer> syncTask;

    private final PasswordField apiKeyField = new PasswordField();
    private final TextArea channelsArea = new TextArea();
    private final Button syncBtn = new Button("Обновить комментарии");
    private final Label status = new Label("Готово");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final ComboBox<Db.Channel> channelBox = new ComboBox<>();
    private final TextField searchField = new TextField();
    private final ListView<Db.CommentRow> list = new ListView<>();
    private Stage widget;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        db = new Db();
        config = Config.load();
        if (config.channels.isEmpty()) config.channels = db.channelLinks(); // каналы из встроенной БД

        apiKeyField.setPromptText("API-ключ YouTube Data API v3");
        apiKeyField.setText(config.apiKey);
        Hyperlink help = new Hyperlink("Как получить ключ");
        help.setOnAction(e -> getHostServices().showDocument(
                "https://developers.google.com/youtube/v3/getting-started"));

        channelsArea.setPromptText("Ссылки на каналы, по одной в строке\nнапр. https://www.youtube.com/@MrBeast");
        channelsArea.setPrefRowCount(3);
        channelsArea.setText(String.join("\n", config.channels));

        spinner.setPrefSize(20, 20);
        spinner.setVisible(false);
        syncBtn.setDefaultButton(true);
        syncBtn.setOnAction(e -> startSync());
        Button widgetBtn = new Button("Виджет");
        widgetBtn.setOnAction(e -> toggleWidget());

        HBox keyRow = new HBox(8, new Label("API-ключ:"), apiKeyField, help);
        HBox.setHgrow(apiKeyField, Priority.ALWAYS);
        keyRow.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(8, syncBtn, widgetBtn, spinner, status);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox settings = new VBox(8, keyRow, new Label("Каналы:"), channelsArea, actions);
        settings.setPadding(new Insets(10));

        channelBox.setOnAction(e -> refreshList());
        searchField.setPromptText("Поиск по тексту или автору");
        searchField.textProperty().addListener((o, a, b) -> refreshList());
        HBox filters = new HBox(8, channelBox, searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        filters.setPadding(new Insets(0, 10, 8, 10));

        list.setCellFactory(lv -> new CommentCell());
        list.setPlaceholder(new Label("Комментариев пока нет. Введите ключ и каналы, затем нажмите «Обновить»."));

        BorderPane root = new BorderPane();
        root.setTop(new VBox(settings, filters));
        root.setCenter(list);

        reloadChannels();
        refreshList();

        stage.getIcons().add(new javafx.scene.image.Image(App.class.getResourceAsStream("/icon.png")));
        stage.setTitle("CommentsCloset");
        stage.setScene(new Scene(root, 900, 700));
        stage.show();
    }

    private void saveConfig() {
        config.apiKey = apiKeyField.getText().trim();
        config.channels = Arrays.stream(channelsArea.getText().split("\\R"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        config.save();
    }

    private void startSync() {
        if (syncTask != null && syncTask.isRunning()) {
            syncTask.cancel(true);
            return;
        }
        saveConfig();
        if (config.apiKey.isEmpty()) { status.setText("Введите API-ключ"); return; }
        if (config.channels.isEmpty()) { status.setText("Добавьте хотя бы один канал"); return; }

        List<String> links = config.channels;
        SyncService svc = new SyncService(db, new YouTubeClient(config.apiKey));
        syncTask = new Task<>() {
            @Override protected Integer call() throws Exception {
                return svc.sync(links, msg -> updateMessage(msg));
            }
        };
        status.textProperty().bind(syncTask.messageProperty());
        spinner.setVisible(true);
        syncBtn.setText("Остановить");
        syncTask.setOnSucceeded(e -> finishSync("Готово, новых комментариев: " + syncTask.getValue()));
        syncTask.setOnFailed(e -> finishSync("Ошибка: " + syncTask.getException().getMessage()));
        syncTask.setOnCancelled(e -> finishSync("Остановлено"));
        Thread t = new Thread(syncTask, "sync");
        t.setDaemon(true);
        t.start();
    }

    private void finishSync(String message) {
        status.textProperty().unbind();
        status.setText(message);
        spinner.setVisible(false);
        syncBtn.setText("Обновить комментарии");
        reloadChannels();
        refreshList();
    }

    private void reloadChannels() {
        Db.Channel selected = channelBox.getValue();
        channelBox.getItems().setAll(ALL);
        channelBox.getItems().addAll(db.channels());
        channelBox.getItems().stream()
                .filter(c -> selected != null && java.util.Objects.equals(c.id(), selected.id()))
                .findFirst().ifPresentOrElse(channelBox::setValue, () -> channelBox.setValue(ALL));
    }

    private void refreshList() {
        Db.Channel ch = channelBox.getValue();
        list.getItems().setAll(db.comments(ch == null ? null : ch.id(), searchField.getText(), 1000, false));
    }

    // ---------- виджет ----------

    private static final int WIDGET_SIZE = 5;
    private static final int WIDGET_POOL = 100;
    private static final int WIDGET_MAX_LINES = 7;

    /** Показывает 5 последних комментариев; раз в 10 секунд верхний исчезает, снизу появляется следующий. */
    private void toggleWidget() {
        if (widget != null) { widget.close(); return; }

        Label title = new Label("Зашкафные комментарии");
        title.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
        Label close = new Label("✕");
        close.setStyle("-fx-text-fill: #aaaaaa; -fx-cursor: hand;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(title, spacer, close);

        VBox cards = new VBox(8);
        VBox box = new VBox(8, top, cards);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: rgba(25,25,30,0.92); -fx-background-radius: 10;");

        Stage w = new Stage(StageStyle.TRANSPARENT);
        w.setAlwaysOnTop(true);
        box.setPrefWidth(380);
        Scene sc = new Scene(box); // высота по содержимому
        sc.setFill(null);
        w.setScene(sc);
        // подгоняем окно под содержимое при каждом изменении высоты
        box.heightProperty().addListener((o, a, b) -> Platform.runLater(w::sizeToScene));
        w.setTitle("CommentsCloset");

        double[] drag = new double[2];
        box.setOnMousePressed(e -> { drag[0] = e.getScreenX() - w.getX(); drag[1] = e.getScreenY() - w.getY(); });
        box.setOnMouseDragged(e -> { w.setX(e.getScreenX() - drag[0]); w.setY(e.getScreenY() - drag[1]); });

        // очередь: свежие комментарии от новых к старым; после конца идём по кругу
        java.util.ArrayDeque<Db.CommentRow> queue = new java.util.ArrayDeque<>();
        Runnable reload = () -> {
            Db.Channel ch = channelBox.getValue();
            queue.clear();
            queue.addAll(db.comments(ch == null ? null : ch.id(), null, WIDGET_POOL, false));
        };
        java.util.function.Supplier<Node> nextCard = () -> {
            if (queue.isEmpty()) reload.run();
            return queue.isEmpty() ? null : widgetCard(queue.poll());
        };

        reload.run();
        for (int i = 0; i < WIDGET_SIZE; i++) {
            Node n = nextCard.get();
            if (n != null) cards.getChildren().add(n);
        }
        if (cards.getChildren().isEmpty()) cards.getChildren().add(new Label("Нет комментариев"));

        Timeline tl = new Timeline(new KeyFrame(Duration.seconds(10), e -> {
            if (cards.getChildren().size() < WIDGET_SIZE) { // пока было пусто — пробуем заполнить
                cards.getChildren().removeIf(n -> n instanceof Label);
                while (cards.getChildren().size() < WIDGET_SIZE) {
                    Node n = nextCard.get();
                    if (n == null) return;
                    cards.getChildren().add(n);
                }
                return;
            }
            Node first = cards.getChildren().get(0);
            FadeTransition out = new FadeTransition(Duration.millis(500), first);
            out.setToValue(0);
            out.setOnFinished(ev -> {
                cards.getChildren().remove(first);
                Node n = nextCard.get();
                if (n != null) {
                    n.setOpacity(0);
                    cards.getChildren().add(n);
                    FadeTransition in = new FadeTransition(Duration.millis(500), n);
                    in.setToValue(1);
                    in.play();
                }
            });
            out.play();
        }));
        tl.setCycleCount(Timeline.INDEFINITE);
        tl.play();
        close.setOnMouseClicked(e -> w.close());
        w.setOnHidden(e -> { tl.stop(); widget = null; });
        widget = w;
        w.show();
    }

    private Node widgetCard(Db.CommentRow c) {
        Label meta = new Label(c.author() + " · " + c.channelTitle());
        meta.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
        Label text = new Label(c.text());
        text.setWrapText(true);
        text.setMaxWidth(340);
        text.setStyle("-fx-text-fill: white; -fx-font-size: 13px;");
        // до WIDGET_MAX_LINES строк (дальше многоточие); короткий комментарий занимает меньше места
        javafx.scene.text.Text probe = new javafx.scene.text.Text("A");
        probe.setFont(javafx.scene.text.Font.font(text.getFont().getFamily(), 13));
        text.setMaxHeight(Math.ceil(probe.getLayoutBounds().getHeight()) * WIDGET_MAX_LINES);
        Label video = new Label("▶ " + c.videoTitle());
        video.setMaxWidth(340);
        video.setStyle("-fx-text-fill: #6fa8ff; -fx-font-size: 11px;");
        VBox card = new VBox(2, meta, text, video);
        card.setMinHeight(Region.USE_PREF_SIZE);
        card.setStyle("-fx-cursor: hand;");
        // клик (не перетаскивание) открывает видео в браузере
        card.setOnMouseClicked(e -> {
            if (e.isStillSincePress()) getHostServices().showDocument("https://www.youtube.com/watch?v=" + c.videoId());
        });
        return card;
    }

    @Override
    public void stop() {
        if (syncTask != null) syncTask.cancel(true);
        if (widget != null) widget.close();
        db.close();
        Platform.exit();
    }

    // ---------- ячейка списка ----------

    private static class CommentCell extends ListCell<Db.CommentRow> {
        private final Label head = new Label();
        private final Label body = new Label();
        private final VBox box = new VBox(2, head, body);

        CommentCell() {
            head.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            body.setWrapText(true);
            setPrefWidth(0); // ширина по ListView, иначе перенос строк не работает
        }

        @Override
        protected void updateItem(Db.CommentRow c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) { setGraphic(null); return; }
            head.setText("%s%s · %s · «%s» · %s · 👍 %d".formatted(c.reply() ? "↳ " : "", c.author(),
                    c.channelTitle(), c.videoTitle(), c.publishedAt().replace('T', ' ').replace("Z", ""), c.likes()));
            body.setText(c.text());
            body.setMaxWidth(Math.max(200, getListView().getWidth() - 40));
            setGraphic(box);
        }
    }
}
