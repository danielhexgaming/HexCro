package com.example.hexcro;

oneway interface IHexCroCallback {
    void onLogLine(String line);
    void onLogError(String error);
}