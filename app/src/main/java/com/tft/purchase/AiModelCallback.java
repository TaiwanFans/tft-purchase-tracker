package com.tft.purchase;

public interface AiModelCallback {
    void onSuccess(String response);
    void onFailure(String error);
}
