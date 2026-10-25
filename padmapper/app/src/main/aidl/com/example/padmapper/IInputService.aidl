package com.example.padmapper;

interface IInputService {
    void destroy() = 16777114;          // Shizuku-reserved transaction id, required
    void setProfile(String json) = 1;
    void start(String deviceName) = 2;  // starts evdev reader + router + injector in the shell process
    void stop() = 3;
}
