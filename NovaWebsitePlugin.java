package com.dark.novawebsite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;

public final class NovaWebsitePlugin extends JavaPlugin implements Listener {
    private HttpServer server;
    private final Deque<ChatLine> chat = new ArrayDeque<>();
    private volatile Snapshot snapshot = new Snapshot("Minecraft Server", 0, List.of());
    private int maxMessages;

    @Override public void onEnable() {
        saveDefaultConfig();
        maxMessages = Math.max(1, getConfig().getInt("max-chat-messages", 60));
        Bukkit.getPluginManager().registerEvents(this, this);
        updateSnapshot();
        Bukkit.getScheduler().runTaskTimer(this, this::updateSnapshot, 20L, 20L);
        try {
            int port = getConfig().getInt("port", 8080);
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "NovaWebsite-HTTP"); t.setDaemon(true); return t; }));
            server.start();
            getLogger().info("Website listening on port " + port + ". Open http://YOUR-SERVER-IP:" + port);
        } catch (IOException e) { getLogger().severe("Website could not start: " + e.getMessage()); }
    }
    @Override public void onDisable() { if (server != null) server.stop(0); }

    @EventHandler public void onChat(AsyncChatEvent event) {
        ChatLine line = new ChatLine(event.getPlayer().getName(), PlainTextComponentSerializer.plainText().serialize(event.message()), System.currentTimeMillis());
        synchronized (chat) { chat.addLast(line); while (chat.size() > maxMessages) chat.removeFirst(); }
    }
    private void updateSnapshot() {
        List<String> names = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        snapshot = new Snapshot(getConfig().getString("server-name", "Minecraft Server"), Bukkit.getMaxPlayers(), List.copyOf(names));
    }
    private void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { send(ex, 405, "text/plain; charset=utf-8", "GET only"); return; }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/api/status")) {
            Snapshot s = snapshot; StringBuilder b = new StringBuilder("{\"serverName\":\"").append(esc(s.name)).append("\",\"online\":true,\"onlineCount\":").append(s.players.size()).append(",\"maxPlayers\":").append(s.max).append(",\"players\":[");
            for (int i=0;i<s.players.size();i++) { if(i>0)b.append(','); b.append('\"').append(esc(s.players.get(i))).append('\"'); } b.append("]}");
            send(ex,200,"application/json; charset=utf-8",b.toString());
        } else if (path.equals("/api/chat")) {
            List<ChatLine> copy; synchronized(chat){copy=new ArrayList<>(chat);} StringBuilder b=new StringBuilder("{\"messages\":[");
            for(int i=0;i<copy.size();i++){if(i>0)b.append(',');ChatLine m=copy.get(i);String time=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(m.time));b.append("{\"player\":\"").append(esc(m.player)).append("\",\"message\":\"").append(esc(m.message)).append("\",\"time\":\"").append(esc(time)).append("\"}");} b.append("]}");
            send(ex,200,"application/json; charset=utf-8",b.toString());
        } else if (path.equals("/")) {
            try(InputStream in=getResource("index.html")){if(in==null){send(ex,500,"text/plain; charset=utf-8","Missing index.html");return;}String html=new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("{{TITLE}}",htmlEsc(getConfig().getString("website-title","Nova Network"))).replace("{{DISCORD_URL}}",htmlEsc(getConfig().getString("discord-url","https://discord.gg/PUT-YOUR-INVITE-HERE")));send(ex,200,"text/html; charset=utf-8",html);}
        } else send(ex,404,"text/plain; charset=utf-8","Not found");
    }
    private static void send(HttpExchange ex,int code,String type,String body)throws IOException{byte[] data=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type",type);ex.getResponseHeaders().set("Cache-Control","no-store");ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");ex.sendResponseHeaders(code,data.length);try(OutputStream out=ex.getResponseBody()){out.write(data);}}
    private static String esc(String s){StringBuilder b=new StringBuilder();for(char c:s.toCharArray())switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}return b.toString();}
    private static String htmlEsc(String s){return s.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    private record ChatLine(String player,String message,long time){}
    private record Snapshot(String name,int max,List<String> players){}
}
