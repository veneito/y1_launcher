package com.themoon.y1.managers;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LastFmManager {
    private static final String TAG = "LastFmManager";
    // 💡 공개 API Key (웹 브라우저 로그인 창 띄우기용 - 공개 식별자이므로 안전합니다)
    private static final String API_KEY = "2165415dd222229ee5bcfb7f93145fe1";
    // 🚀 모든 통신 및 보안 서명(API_SECRET) 처리를 전담하는 Vercel 프록시 URL
    // Fork: scrobble to the self-hosted Maloja (its Last.fm-compatible API) instead of Last.fm.
    // Log in with any username and a Maloja API key as the password.
    private static final String PROXY_URL = "https://maloja.veneito.dev/apis/audioscrobbler/2.0/"; // login = key check
    private static final String MALOJA_SCROBBLE_URL = "https://maloja.veneito.dev/apis/mlj_1/newscrobble";
    private static final String CALLBACK_URL = "y1://lastfm-callback";

    private static LastFmManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final OkHttpClient httpClient;
    private final ScrobbleDatabaseHelper dbHelper;

    private String sessionKey;
    private String username;
    private String appToken;
    
    private boolean isScrobblingEnabled = false;

    private LastFmManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("lastfm_prefs", Context.MODE_PRIVATE);
        this.httpClient = getMalojaHttpClient(this.context);
        this.dbHelper = new ScrobbleDatabaseHelper(this.context);
        this.sessionKey = prefs.getString("session_key", null);
        this.username = prefs.getString("username", null);
        this.appToken = prefs.getString("app_token", null);
        this.isScrobblingEnabled = (this.sessionKey != null);
    }

    // Android 4.2's own TLS can't talk to Cloudflare, and its CA store predates Let's Encrypt's
    // ISRG roots. So: bundled Conscrypt for TLS 1.2, and only the ISRG roots in assets/isrg_roots.pem
    // as trust anchors (real certificate + hostname checks; this carries the Maloja API key).
    private static OkHttpClient getMalojaHttpClient(Context context) {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            ks.load(null, null);
            java.io.InputStream in = context.getAssets().open("isrg_roots.pem");
            int i = 0;
            for (java.security.cert.Certificate c : java.security.cert.CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("isrg" + i++, c);
            }
            in.close();
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            javax.net.ssl.X509TrustManager tm = (javax.net.ssl.X509TrustManager) tmf.getTrustManagers()[0];
            javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS", org.conscrypt.Conscrypt.newProvider());
            ssl.init(null, new javax.net.ssl.TrustManager[]{tm}, null);
            return new OkHttpClient.Builder().sslSocketFactory(ssl.getSocketFactory(), tm).build();
        } catch (Exception e) {
            Log.e(TAG, "Maloja TLS setup failed: " + e);
            return new OkHttpClient();
        }
    }

    public static LastFmManager getInstance(Context context) {
        if (instance == null) {
            instance = new LastFmManager(context);
        }
        return instance;
    }

    public boolean isEnabled() {
        return isScrobblingEnabled && sessionKey != null;
    }

    public String getUsername() {
        return username;
    }

    public String getAppToken() {
        return appToken != null ? appToken : prefs.getString("app_token", null);
    }

    /**
     * 🚀 Last.fm 공식 웹 인증 브라우저 창 열기
     * 사용자 비밀번호 입력 없이 1회 브라우저 승인 방식으로 안전하게 진행됩니다.
     */
    public void startWebAuth(Context ctx) {
        try {
            String authUrl = "http://www.last.fm/api/auth/?api_key=" + API_KEY + "&cb=" + android.net.Uri.encode(CALLBACK_URL);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authUrl));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Last.fm web auth: " + e.getMessage());
        }
    }

    /**
     * 🚀 웹 인증 후 수신한 Token을 Vercel 백엔드 프록시로 전달하여
     * Last.fm Session Key와 서비스 전용 JWT 토큰을 동시에 발급받습니다.
     */
    public void exchangeTokenWithBackend(String token, final LoginCallback callback) {
        String url = PROXY_URL + "?method=auth.getSession&token=" + token;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String bodyStr = response.body().string();
                        JSONObject json = new JSONObject(bodyStr);

                        String key = null;
                        String name = null;

                        if (json.has("session")) {
                            JSONObject session = json.getJSONObject("session");
                            key = session.getString("key");
                            name = session.getString("name");
                        } else if (json.has("sessionKey")) {
                            key = json.getString("sessionKey");
                            name = json.optString("username", "");
                        } else if (json.has("error")) {
                            if (callback != null) callback.onError(json.getString("message"));
                            return;
                        }

                        if (key != null) {
                            sessionKey = key;
                            username = name;
                            if (json.has("appToken")) {
                                appToken = json.getString("appToken");
                            } else if (json.has("token")) {
                                appToken = json.getString("token");
                            }
                            isScrobblingEnabled = true;

                            SharedPreferences.Editor editor = prefs.edit()
                                    .putString("session_key", sessionKey)
                                    .putString("username", username);
                            if (appToken != null) {
                                editor.putString("app_token", appToken);
                            }
                            editor.apply();

                            if (callback != null) callback.onSuccess();
                        } else {
                            if (callback != null) callback.onError("Invalid session response from server");
                        }
                    } else {
                        if (callback != null) callback.onError("HTTP Error: " + response.code());
                    }
                } catch (Exception e) {
                    if (callback != null) callback.onError(e.getMessage());
                }
            }
        });
    }

    public void login(final String user, final String password, final LoginCallback callback) {
        FormBody formBody = new FormBody.Builder()
                .add("method", "auth.getMobileSession")
                .add("username", user)
                .add("password", password)
                .add("format", "json")
                .build();

        Request request = new Request.Builder()
                .url(PROXY_URL)
                .post(formBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        String bodyStr = response.body().string();
                        JSONObject json = new JSONObject(bodyStr);
                        if (json.has("session")) {
                            JSONObject session = json.getJSONObject("session");
                            sessionKey = session.getString("key");
                            username = session.optString("name", user); // Maloja's JSON reply has no name
                            isScrobblingEnabled = true;

                            prefs.edit()
                                    .putString("session_key", sessionKey)
                                    .putString("username", username)
                                    .putString("maloja_key", password) // to re-login: Maloja forgets sessions on restart
                                    .apply();
                            
                            if (callback != null) callback.onSuccess();
                        } else if (json.has("error")) {
                            if (callback != null) callback.onError(json.getString("message"));
                        } else {
                            if (callback != null) callback.onError("Unknown error");
                        }
                    } else {
                        if (callback != null) callback.onError("HTTP Error: " + response.code());
                    }
                } catch (Exception e) {
                    if (callback != null) callback.onError(e.getMessage());
                }
            }
        });
    }

    public void logout() {
        sessionKey = null;
        username = null;
        appToken = null;
        isScrobblingEnabled = false;
        prefs.edit().clear().apply();
        dbHelper.clearQueue();
    }

    // Queues a scrobble in SQLite
    public void scrobble(String track, String artist, String album, long timestampUnix) {
        if (!isEnabled()) return;
        dbHelper.addScrobble(track, artist, album, timestampUnix);
        processQueue(); // Attempt to process immediately
    }

    private final java.util.concurrent.atomic.AtomicBoolean isProcessingQueue = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Process the queue (up to 50 at a time per Last.fm batch limits, looping until empty)
    public void processQueue() {
        if (!isEnabled()) return;
        if (!isProcessingQueue.compareAndSet(false, true)) {
            return; // 이미 다른 스레드에서 전송 작업 중이면 중복 실행 방지
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String key = prefs.getString("maloja_key", null);
                    if (key == null) return; // no saved key: log in again on the web page
                    while (true) {
                        List<ScrobbleItem> batch = dbHelper.getBatch(1);
                        if (batch.isEmpty()) break;
                        ScrobbleItem item = batch.get(0);

                        // Maloja's own API, not its Last.fm one: that one drops the album (no covers, no album charts)
                        FormBody.Builder form = new FormBody.Builder()
                                .add("key", key)
                                .add("artist", item.artist)
                                .add("title", item.track)
                                .add("time", String.valueOf(item.timestamp));
                        if (item.album != null && !item.album.isEmpty()) form.add("album", item.album);

                        Response response = httpClient.newCall(new Request.Builder()
                                .url(MALOJA_SCROBBLE_URL).post(form.build()).build()).execute();
                        String bodyStr = response.body() != null ? response.body().string() : "";
                        if (response.isSuccessful() && bodyStr.contains("\"success\"")) {
                            dbHelper.removeScrobble(item.id);
                        } else {
                            Log.e(TAG, "Scrobble failed (" + response.code() + "): " + bodyStr);
                            break; // keep it queued; retried on the next scrobble or Wi-Fi connect
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Scrobble exception: " + e.getMessage());
                } finally {
                    isProcessingQueue.set(false);
                }
            }
        }).start();
    }

    public interface LoginCallback {
        void onSuccess();
        void onError(String errorMsg);
    }

    // SQLite Queue Helper
    private static class ScrobbleItem {
        long id;
        String track;
        String artist;
        long timestamp;
        String album;
    }

    private static class ScrobbleDatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "scrobbles.db";
        private static final int DATABASE_VERSION = 2; // v2: album column
        private static final String TABLE_SCROBBLES = "scrobbles";

        public ScrobbleDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            String CREATE_TABLE = "CREATE TABLE " + TABLE_SCROBBLES + " (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "track TEXT," +
                    "artist TEXT," +
                    "timestamp INTEGER," +
                    "album TEXT)";
            db.execSQL(CREATE_TABLE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE " + TABLE_SCROBBLES + " ADD COLUMN album TEXT"); // keeps queued plays
        }

        public void addScrobble(String track, String artist, String album, long timestamp) {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("track", track);
            values.put("artist", artist);
            values.put("timestamp", timestamp);
            values.put("album", album);
            db.insert(TABLE_SCROBBLES, null, values);
            db.close();
        }

        public List<ScrobbleItem> getBatch(int limit) {
            List<ScrobbleItem> list = new ArrayList<>();
            SQLiteDatabase db = this.getReadableDatabase();
            Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_SCROBBLES + " ORDER BY id ASC LIMIT " + limit, null);
            if (cursor.moveToFirst()) {
                do {
                    ScrobbleItem item = new ScrobbleItem();
                    item.id = cursor.getLong(0);
                    item.track = cursor.getString(1);
                    item.artist = cursor.getString(2);
                    item.timestamp = cursor.getLong(3);
                    item.album = cursor.getString(cursor.getColumnIndex("album"));
                    list.add(item);
                } while (cursor.moveToNext());
            }
            cursor.close();
            db.close();
            return list;
        }

        public void removeScrobble(long id) {
            SQLiteDatabase db = this.getWritableDatabase();
            db.delete(TABLE_SCROBBLES, "id=?", new String[]{String.valueOf(id)});
            db.close();
        }

        public void clearQueue() {
            SQLiteDatabase db = this.getWritableDatabase();
            db.delete(TABLE_SCROBBLES, null, null);
            db.close();
        }
    }
}
