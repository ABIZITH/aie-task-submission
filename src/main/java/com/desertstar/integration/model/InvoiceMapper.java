package com.desertstar.integration.model;

import com.desertstar.integration.json.JsonPath;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms a validated source ERP payload into the normalized invoice structure.
 * See docs/mapping.csv for the full source-to-target field mapping table this implements.
 */
public final class InvoiceMapper {

    private static final BigDecimal STANDARD_RATE = new BigDecimal("5");

    public Map<String, Object> map(Map<String, Object> root) {
        Map<String, Object> invoice = JsonPath.obj(root, "invoice");
        Map<String, Object> normalized = new LinkedHashMap<>();

        normalized.put("source", sourceBlock(root));
        normalized.put("invoiceNo", JsonPath.str(invoice, "invoiceNo"));
        normalized.put("issueDate", JsonPath.str(invoice, "issueDate"));
        normalized.put("documentType", JsonPath.str(invoice, "documentType"));
        normalized.put("currency", JsonPath.str(invoice, "currency"));
        normalized.put("seller", party(JsonPath.obj(invoice, "seller"), true));
        normalized.put("buyer", party(JsonPath.obj(invoice, "buyer"), false));
        normalized.put("lines", lines(JsonPath.arr(invoice, "lines")));
        normalized.put("totals", totals(JsonPath.obj(invoice, "totals")));
        normalized.put("payment", payment(JsonPath.obj(invoice, "payment")));

        String originalInvoiceNo = JsonPath.str(invoice, "originalInvoiceNo");
        normalized.put("originalInvoiceNo", originalInvoiceNo);

        return normalized;
    }

    private Map<String, Object> sourceBlock(Map<String, Object> root) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", JsonPath.str(root, "sourceName"));
        source.put("version", JsonPath.str(root, "sourceVersion"));
        return source;
    }

    private Map<String, Object> party(Map<String, Object> src, boolean includeTrnAndEmirate) {
        if (src == null) return null;
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("legalName", JsonPath.str(src, "legalName"));
        if (includeTrnAndEmirate || src.containsKey("trn")) {
            party.put("trn", JsonPath.str(src, "trn"));
        }
        party.put("addressLine1", JsonPath.str(src, "addressLine1"));
        party.put("city", JsonPath.str(src, "city"));
        if (includeTrnAndEmirate) {
            party.put("emirate", JsonPath.str(src, "emirate"));
        }
        party.put("country", JsonPath.str(src, "country"));
        return party;
    }

    private List<Object> lines(List<Object> srcLines) {
        List<Object> result = new ArrayList<>();
        if (srcLines == null) return result;
        for (Object o : srcLines) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) o;

            Double quantity = JsonPath.num(line, "quantity");
            Double unitPrice = JsonPath.num(line, "unitPrice");
            String taxCategory = JsonPath.str(line, "taxCategory");
            BigDecimal rate = "STANDARD".equals(taxCategory) ? STANDARD_RATE : BigDecimal.ZERO;

            Map<String, Object> normalizedLine = new LinkedHashMap<>();
            normalizedLine.put("lineId", JsonPath.str(line, "lineId"));
            normalizedLine.put("sku", JsonPath.str(line, "sku"));
            normalizedLine.put("description", JsonPath.str(line, "description"));
            normalizedLine.put("quantity", quantity);
            normalizedLine.put("unitPrice", unitPrice);
            normalizedLine.put("taxCategory", taxCategory);
            normalizedLine.put("taxRate", Money.toDouble(rate));

            if (quantity != null && unitPrice != null) {
                BigDecimal lineNet = Money.round(BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(unitPrice)));
                BigDecimal lineTax = Money.round(lineNet.multiply(rate).divide(new BigDecimal("100")));
                BigDecimal lineGross = Money.round(lineNet.add(lineTax));
                normalizedLine.put("lineNet", Money.toDouble(lineNet));
                normalizedLine.put("lineTax", Money.toDouble(lineTax));
                normalizedLine.put("lineGross", Money.toDouble(lineGross));
            }
            result.add(normalizedLine);
        }
        return result;
    }

    private Map<String, Object> totals(Map<String, Object> src) {
        if (src == null) return null;
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("netAmount", JsonPath.num(src, "netAmount"));
        totals.put("taxAmount", JsonPath.num(src, "taxAmount"));
        totals.put("grossAmount", JsonPath.num(src, "grossAmount"));
        totals.put("prepaidAmount", JsonPath.num(src, "prepaidAmount"));
        totals.put("amountDue", JsonPath.num(src, "amountDue"));
        return totals;
    }

    private Map<String, Object> payment(Map<String, Object> src) {
        if (src == null) return null;
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("method", JsonPath.str(src, "method"));
        payment.put("terms", JsonPath.str(src, "terms"));
        return payment;
    }
}
