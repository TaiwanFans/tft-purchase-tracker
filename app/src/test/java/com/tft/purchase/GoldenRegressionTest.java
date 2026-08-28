package com.tft.purchase;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression guards derived from the real 2026-08-13 quotation supplied by the user.
 * These tests intentionally lock down the failure modes observed in v2.0.18.
 */
public class GoldenRegressionTest {

    @Test
    public void ownCompanyLegalNameMustNeverBecomeCounterparty() {
        assertTrue(AiResultValidator.isSelfCompany("全益畜牧器具有限公司"));
        assertTrue(AiResultValidator.isSelfCompany("全益畜牧器具公司"));
        assertTrue(AiResultValidator.isSelfCompany("全益畜牧器具有限公司 報價單"));
        assertFalse(AiResultValidator.isSelfCompany("君誠環保顧問有限公司"));
    }

    @Test
    public void fourDigitSourceLineNumberIsPreservedAndMissingDeliveryDateIsAllowed() {
        PurchaseOcrParser.ParsedPurchase p = basePurchase();
        PurchaseDbHelper.PurchaseItem i = new PurchaseDbHelper.PurchaseItem();
        i.lineNo = "0001";
        i.description = "母豬涼風扇";
        i.specification = "6英吋，單相220V，防噴霧IP55型";
        i.quantity = "1";
        i.unit = "台";
        i.unitPrice = "480";
        i.subtotal = "480";
        i.deliveryDate = "";
        p.items.add(i);

        AiResultValidator.Validation v = AiResultValidator.validateAndSanitize(p);
        assertEquals("0001", p.items.get(0).lineNo);
        assertEquals("", p.items.get(0).deliveryDate);
        assertTrue(v.reliable);
    }

    @Test
    public void specNumberLeakMustNotCreateArithmeticCandidate() {
        PurchaseOcrParser.ParsedPurchase p = basePurchase();
        PurchaseDbHelper.PurchaseItem i = new PurchaseDbHelper.PurchaseItem();
        i.lineNo = "0010";
        i.description = "母豬涼風扇不銹鋼支架";
        i.specification = "6英吋型，含電解，含螺絲組";
        i.quantity = "6";
        i.unit = "英吋型";
        i.unitPrice = "220";
        i.subtotal = "132000";
        p.items.add(i);

        AiResultValidator.Validation v = AiResultValidator.validateAndSanitize(p);
        PurchaseDbHelper.PurchaseItem out = p.items.get(0);
        assertFalse(v.reliable);
        assertEquals("", out.quantity);
        assertEquals("", out.unit);
        assertTrue(out.note.contains("NUMBER_CONFLICT"));
        assertFalse(out.note.contains("候選數量"));
        assertFalse(out.note.contains("600"));
    }

    @Test
    public void quotationTermsMustNotBecomeProductRows() {
        PurchaseOcrParser.ParsedPurchase p = basePurchase();

        PurchaseDbHelper.PurchaseItem real = new PurchaseDbHelper.PurchaseItem();
        real.lineNo = "0012";
        real.description = "0.75平方2C單相電線";
        real.specification = "10尺，含插頭及2圓R端子";
        real.quantity = "1";
        real.unit = "條";
        real.unitPrice = "100";
        real.subtotal = "100";
        p.items.add(real);

        PurchaseDbHelper.PurchaseItem terms = new PurchaseDbHelper.PurchaseItem();
        terms.lineNo = "0014";
        terms.description = "因應國際原物料持續調漲，本報價單有效期限為2週";
        terms.quantity = "1";
        terms.unit = "式";
        p.items.add(terms);

        AiResultValidator.validateAndSanitize(p);
        assertEquals(1, p.items.size());
        assertEquals("0012", p.items.get(0).lineNo);
    }

    private static PurchaseOcrParser.ParsedPurchase basePurchase() {
        PurchaseOcrParser.ParsedPurchase p = new PurchaseOcrParser.ParsedPurchase();
        p.vendor = "君誠環保顧問有限公司";
        p.location = "彰化縣員林市三愛里合作街123號";
        p.orderNo = "202608130005";
        p.purchaseDate = "2026-08-13";
        return p;
    }
}
