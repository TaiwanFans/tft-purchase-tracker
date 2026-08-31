package com.tft.purchase;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ItemDisplayNameRegressionTest {
    @Test public void mergesSizeBeforeProductName() {
        assertEquals("42寸鐵框", PurchaseDbHelper.displayItemName("鐵框", "42寸"));
    }

    @Test public void doesNotDuplicateSpecificationAlreadyInName() {
        assertEquals("42寸鐵框", PurchaseDbHelper.displayItemName("42寸鐵框", "42寸"));
    }

    @Test public void preservesNameWhenNoSpecification() {
        assertEquals("鐵框", PurchaseDbHelper.displayItemName("鐵框", ""));
    }

    @Test public void compactsMultiLineSpecificationIntoFullName() {
        assertEquals("220V 60HZ馬達", PurchaseDbHelper.displayItemName("馬達", "220V\n60HZ"));
    }
}
