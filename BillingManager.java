package com.squeezer.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.util.Consumer;

import com.android.billingclient.api.*;

import java.util.Collections;

public class BillingManager {

    private static final String PRODUCT_ID = "unlock_desqueeze";
    private static final String PREF_NAME = "BillingPrefs";
    private static final String PREF_KEY_PURCHASED = "isPurchased";

    private BillingClient billingClient;
    private boolean isPurchased = false;

    public interface BillingCallback {
        void onPurchaseComplete();
        void onBillingError(String error);
    }

    public BillingManager(Activity activity, BillingCallback callback) {
        SharedPreferences prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        isPurchased = prefs.getBoolean(PREF_KEY_PURCHASED, false);

        billingClient = BillingClient.newBuilder(activity)
                .setListener((billingResult, purchases) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                        for (Purchase purchase : purchases) {
                            if (purchase.getProducts().contains(PRODUCT_ID)) {
                                handlePurchase(purchase, prefs, callback);
                            }
                        }
                    } else if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.USER_CANCELED) {
                        callback.onBillingError("Billing failed: " + billingResult.getDebugMessage());
                    }
                })
                .enablePendingPurchases()
                .build();

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    checkIfPurchased(activity, callback);
                } else {
                    Log.e("Billing", "Billing setup failed: " + billingResult.getDebugMessage());
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                Log.w("Billing", "Billing service disconnected.");
            }
        });
    }

    private void checkIfPurchased(Context context, BillingCallback callback) {
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                (billingResult, purchaseList) -> {
                    for (Purchase purchase : purchaseList) {
                        if (purchase.getProducts().contains(PRODUCT_ID)) {
                            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                            handlePurchase(purchase, prefs, callback);
                            break;
                        }
                    }
                }
        );
    }

    private void handlePurchase(Purchase purchase, SharedPreferences prefs, BillingCallback callback) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged()) {
            AcknowledgePurchaseParams acknowledgeParams =
                    AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.getPurchaseToken())
                            .build();

            billingClient.acknowledgePurchase(acknowledgeParams, billingResult -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d("Billing", "Purchase acknowledged.");
                    prefs.edit().putBoolean(PREF_KEY_PURCHASED, true).apply();
                    isPurchased = true;
                    callback.onPurchaseComplete();
                }
            });
        } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            prefs.edit().putBoolean(PREF_KEY_PURCHASED, true).apply();
            isPurchased = true;
            callback.onPurchaseComplete();
        }
    }
    public void queryPrice(Consumer<ProductDetails> priceCallback) {
        billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder()
                        .setProductList(
                                Collections.singletonList(
                                        QueryProductDetailsParams.Product.newBuilder()
                                                .setProductId(PRODUCT_ID)
                                                .setProductType(BillingClient.ProductType.INAPP)
                                                .build()
                                )
                        )
                        .build(),
                (billingResult, productDetailsList) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && !productDetailsList.isEmpty()) {
                        priceCallback.accept(productDetailsList.get(0));
                    }
                }
        );
    }
    public boolean isPurchased() {
        return isPurchased;
    }

    public void launchPurchaseFlow(Activity activity) {
        billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder()
                        .setProductList(
                                Collections.singletonList(
                                        QueryProductDetailsParams.Product.newBuilder()
                                                .setProductId(PRODUCT_ID)
                                                .setProductType(BillingClient.ProductType.INAPP)
                                                .build()
                                )
                        )
                        .build(),
                (billingResult, productDetailsList) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && !productDetailsList.isEmpty()) {
                        ProductDetails productDetails = productDetailsList.get(0);

                        BillingFlowParams.ProductDetailsParams productDetailsParams =
                                BillingFlowParams.ProductDetailsParams.newBuilder()
                                        .setProductDetails(productDetails)
                                        .build();

                        BillingFlowParams billingFlowParams =
                                BillingFlowParams.newBuilder()
                                        .setProductDetailsParamsList(Collections.singletonList(productDetailsParams))
                                        .build();

                        billingClient.launchBillingFlow(activity, billingFlowParams);
                    } else {
                        Log.e("Billing", "Product details not found or error: " + billingResult.getDebugMessage());
                    }
                }
        );
    }
}