package com.example.hexcro;

import com.example.hexcro.IHexCroCallback;

interface IHexCroService {
    void setConfig(String webhookUrl, String privateServer) = 1;
    void startLogcat(IHexCroCallback callback) = 2;
    void stopLogcat() = 3;
    void destroy() = 16777114;
}