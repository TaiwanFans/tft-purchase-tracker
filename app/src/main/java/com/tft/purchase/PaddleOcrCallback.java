package com.tft.purchase;

public interface PaddleOcrCallback {
    void onSuccess(String structuredEvidence);
    void onFailure(String error);
}
