package com.tft.purchase;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression guards derived from real documents supplied by the user. */
public class GoldenRegressionTest {

    @Test
    public void ownCompanyLegalNameMustNeverBecomeCounterparty() {
        assertTrue(AiResultValidator.isSelfCompany("全益畜牧器具有限公司"));
        assertTrue(AiResultValidator.isSelfCompany("全益畜牧器具公司"));
        assertTrue(AiResultValidator.isSelfCompany("全益畜牧器具有限公司 採購單"));
        assertTrue(AiResultValidator.isSelfCompany("台灣電扇科技有限公司"));
        assertFalse(AiResultValidator.isSelfCompany("強盛企業社(強發金屬有限公司)"));
    }

    @Test
    public void fixedPurchaseFormSelectsExactlyEightSemanticColumns() {
        // Representative real scan has an extra far-right page edge after the actual table border.
        TableGridDetector.CanonicalColumns c = TableGridDetector.canonicalizePurchaseColumns(Arrays.asList(
                0.035f, 0.066f, 0.464f, 0.534f, 0.574f, 0.635f, 0.731f, 0.846f, 0.963f, 0.996f));
        assertTrue(c.matched);
        assertEquals(9, c.lines.size());
        assertEquals(0.035f, c.lines.get(0), 0.002f);
        assertEquals(0.963f, c.lines.get(8), 0.002f);
    }

    @Test
    public void fixedTemplateMustOverrideAiCrossColumnGuess() {
        PurchaseOcrParser.ParsedPurchase p = basePurchase();
        p.vendor = "台灣電扇科技有限公司"; // deliberately wrong model guess
        PurchaseDbHelper.PurchaseItem bad = new PurchaseDbHelper.PurchaseItem();
        bad.lineNo = "001";
        bad.description = "36吋風葉";
        bad.quantity = "304"; // unit price leaked into quantity
        bad.unitPrice = "20";
        bad.deliveryDate = "2026-04-23";
        p.items.add(bad);

        String ev = "[FIXED_PURCHASE_ORDER_V220]\n" +
                "matched=true confidence=0.960 formula=test\n" +
                "FIXED_HEADER\tvendor=強盛企業社(強發金屬有限公司) (CB006)\tlocation=彰化縣福興鄉三和村南興街93-36號\torder_no=202604230001\tpurchase_date=2026-04-23\n" +
                "FIXED_ROW\tline_no=001\tdescription=36吋耐隆纖維三葉風葉（紅葉紅盤，16芯，60型）\tquantity=20\tunit=片\tunit_price=304\tsubtotal=6080\tdelivery_date=2026-04-28\tnote=配件已備好\n" +
                "FIXED_ROW\tline_no=002\tdescription=以下空白\tquantity=1\tunit=式\tunit_price=0\tsubtotal=\tdelivery_date=2026-04-28\tnote=\n" +
                "[/FIXED_PURCHASE_ORDER_V220]";

        assertTrue(FixedPurchaseOrderTemplateParser.apply(p, ev));
        assertEquals("強盛企業社(強發金屬有限公司) (CB006)", p.vendor);
        assertEquals("202604230001", p.orderNo);
        assertEquals("2026-04-23", p.purchaseDate);
        assertEquals(1, p.items.size());
        assertEquals("001", p.items.get(0).lineNo);
        assertEquals("20", p.items.get(0).quantity);
        assertEquals("304", p.items.get(0).unitPrice);
        assertEquals("6080", p.items.get(0).subtotal);
        assertEquals("2026-04-28", p.items.get(0).deliveryDate);
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
