package com.eddyizm.tempus.subsonic;

import com.eddyizm.tempus.subsonic.jellyfin.JellyfinCredential;
import com.eddyizm.tempus.subsonic.utils.StringUtil;

import java.util.UUID;

public class SubsonicPreferences {
    private String serverUrl;
    private String username;
    private String clientName = "Drift";
    private SubsonicAuthentication authentication;

    public String getServerUrl() {
        return serverUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getClientName() {
        return clientName;
    }

    public SubsonicAuthentication getAuthentication() {
        return authentication;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public void setAuthentication(String password, String token, String salt, boolean isLowSecurity) {
        if (password != null) {
            // Drift: the Jellyfin/Emby session travels in clear in the p parameter, where the emulation
            // interceptor reads it back; a token+salt hash would make it unreadable
            boolean isMediaBrowser = JellyfinCredential.isCredential(password);
            this.authentication = new SubsonicAuthentication(password, isLowSecurity || isMediaBrowser);
            if (isMediaBrowser) return;
        }

        if (token != null && salt != null) {
            this.authentication = new SubsonicAuthentication(token, salt);
        }
    }

    public static class SubsonicAuthentication {
        private String password;
        private String salt;
        private String token;

        public SubsonicAuthentication(String password, boolean isLowSecurity) {
            if (isLowSecurity) {
                this.password = password;
            } else {
                update(password);
            }
        }

        public SubsonicAuthentication(String token, String salt) {
            this.token = token;
            this.salt = salt;
        }

        public String getPassword() {
            return password;
        }

        public String getSalt() {
            return salt;
        }

        public String getToken() {
            return token;
        }

        void update(String password) {
            this.salt = UUID.randomUUID().toString();
            this.token = StringUtil.tokenize(password + salt);
        }
    }
}
