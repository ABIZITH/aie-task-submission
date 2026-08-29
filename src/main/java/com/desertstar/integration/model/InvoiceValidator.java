package com.desertstar.integration.model;

import com.desertstar.integration.json.JsonPath;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Implements the "Simplified Assessment Rules" (assignment section 3) against
 * the raw ERP payload shape (assignment section 4).
 *
 * Deliberately does NOT implement real UAE tax / PINT AE rules beyond what the
 * assignment supplies — anything beyond that is flagged as an open question in
 * docs/discovery-and-design.md rather than invented here.
 */
public final class InvoiceValidator {

    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TRN_PATTERN = Pattern.compile("^\\d{15}$");
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("^[A-Za-z]{2}$");
    private static final Set<String> DOC_TYPES = Set.of("INVOICE", "CREDIT_NOTE");
    private static final Set<String> TAX_CATEGORIES = Set.of("STANDARD", "ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE");
    private static final BigDecimal STANDARD_RATE = new BigDecimal("5");

    public List<ValidationError> validate(Map<String, Object> root) {
        List<ValidationError> errors = new ArrayList<>();

        Map<String, Object> invoice = JsonPath.obj(root, "invoice");
        if (invoice == null) {
            errors.add(new ValidationError("invoice", "MISSING_FIELD", "invoice object is required"));
            return errors; // nothing else can be checked meaningfully
        }

        String invoiceNo = JsonPath.str(invoice, "invoiceNo");
        if (isBlank(invoiceNo)) {
            errors.add(new ValidationError("invoice.invoiceNo", "REQUIRED", "invoiceNo must not be empty"));
        }

        String issueDate = JsonPath.str(invoice, "issueDate");
        if (isBlank(issueDate)) {
            errors.add(new ValidationError("invoice.issueDate", "REQUIRED", "issueDate is required"));
        } else if (!DATE_PATTERN.matcher(issueDate).matches() || !isRealDate(issueDate)) {
            errors.add(new ValidationError("invoice.issueDate", "INVALID_FORMAT", "issueDate must use YYYY-MM-DD format"));
        }

        String documentType = JsonPath.str(invoice, "documentType");
        if (isBlank(documentType)) {
            errors.add(new ValidationError("invoice.documentType", "REQUIRED", "documentType is required"));
        } else if (!DOC_TYPES.contains(documentType)) {
            errors.add(new ValidationError("invoice.documentType", "UNSUPPORTED_VALUE",
                    "documentType must be INVOICE or CREDIT_NOTE"));
        }

        String currency = JsonPath.str(invoice, "currency");
        if (isBlank(currency)) {
            errors.add(new ValidationError("invoice.currency", "REQUIRED", "currency is required"));
        } else if (!"AED".equals(currency)) {
            errors.add(new ValidationError("invoice.currency", "UNSUPPORTED_VALUE",
                    "currency must be AED for this prototype (see docs for multi-currency extension plan)"));
        }

        Map<String, Object> seller = JsonPath.obj(invoice, "seller");
        if (seller == null) {
            errors.add(new ValidationError("invoice.seller", "MISSING_FIELD", "seller object is required"));
        } else {
            requireNonBlank(errors, "invoice.seller.legalName", JsonPath.str(seller, "legalName"));
            String trn = JsonPath.str(seller, "trn");
            if (isBlank(trn)) {
                errors.add(new ValidationError("invoice.seller.trn", "REQUIRED", "Seller TRN is required"));
            } else if (!TRN_PATTERN.matcher(trn).matches()) {
                errors.add(new ValidationError("invoice.seller.trn", "INVALID_FORMAT",
                        "Seller TRN must contain exactly 15 numeric characters"));
            }
            requireNonBlank(errors, "invoice.seller.addressLine1", JsonPath.str(seller, "addressLine1"));
            requireNonBlank(errors, "invoice.seller.emirate", JsonPath.str(seller, "emirate"));
            String sellerCountry = JsonPath.str(seller, "country");
            if (isBlank(sellerCountry)) {
                errors.add(new ValidationError("invoice.seller.country", "REQUIRED", "Seller country is required"));
            } else if (!COUNTRY_PATTERN.matcher(sellerCountry).matches()) {
                errors.add(new ValidationError("invoice.seller.country", "INVALID_FORMAT",
                        "Seller country must be a two-letter country code"));
            }
        }

        Map<String, Object> buyer = JsonPath.obj(invoice, "buyer");
        if (buyer == null) {
            errors.add(new ValidationError("invoice.buyer", "MISSING_FIELD", "buyer object is required"));
        } else {
            requireNonBlank(errors, "invoice.buyer.legalName", JsonPath.str(buyer, "legalName"));
            requireNonBlank(errors, "invoice.buyer.addressLine1", JsonPath.str(buyer, "addressLine1"));
            String buyerCountry = JsonPath.str(buyer, "country");
            if (!isBlank(buyerCountry) && !COUNTRY_PATTERN.matcher(buyerCountry).matches()) {
                errors.add(new ValidationError("invoice.buyer.country", "INVALID_FORMAT",
                        "Buyer country must be a two-letter country code"));
            }
        }

        List<Object> lines = JsonPath.arr(invoice, "lines");
        BigDecimal computedNet = BigDecimal.ZERO;
        BigDecimal computedTax = BigDecimal.ZERO;
        if (lines == null || lines.isEmpty()) {
            errors.add(new ValidationError("invoice.lines", "REQUIRED", "At least one invoice line is required"));
        } else {
            for (int i = 0; i < lines.size(); i++) {
                Object lineObj = lines.get(i);
                String path = "invoice.lines[" + i + "]";
                if (!(lineObj instanceof Map)) {
                    errors.add(new ValidationError(path, "INVALID_FORMAT", "Line must be an object"));
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> line = (Map<String, Object>) lineObj;

                requireNonBlank(errors, path + ".lineId", JsonPath.str(line, "lineId"));
                requireNonBlank(errors, path + ".description", JsonPath.str(line, "description"));

                Double quantity = JsonPath.num(line, "quantity");
                if (quantity == null) {
                    errors.add(new ValidationError(path + ".quantity", "REQUIRED", "quantity is required"));
                } else if (quantity <= 0) {
                    errors.add(new ValidationError(path + ".quantity", "OUT_OF_RANGE", "quantity must be greater than zero"));
                }

                Double unitPrice = JsonPath.num(line, "unitPrice");
                if (unitPrice == null) {
                    errors.add(new ValidationError(path + ".unitPrice", "REQUIRED", "unitPrice is required"));
                } else if (unitPrice < 0) {
                    errors.add(new ValidationError(path + ".unitPrice", "OUT_OF_RANGE", "unitPrice must not be negative"));
                }

                String taxCategory = JsonPath.str(line, "taxCategory");
                BigDecimal effectiveRate = BigDecimal.ZERO;
                if (isBlank(taxCategory)) {
                    errors.add(new ValidationError(path + ".taxCategory", "REQUIRED", "taxCategory is required"));
                } else if (!TAX_CATEGORIES.contains(taxCategory)) {
                    errors.add(new ValidationError(path + ".taxCategory", "UNSUPPORTED_VALUE",
                            "taxCategory must be one of STANDARD, ZERO_RATED, EXEMPT, OUT_OF_SCOPE"));
                } else {
                    effectiveRate = "STANDARD".equals(taxCategory) ? STANDARD_RATE : BigDecimal.ZERO;
                }

                if (quantity != null && unitPrice != null) {
                    BigDecimal lineNet = Money.round(BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(unitPrice)));
                    BigDecimal lineTax = Money.round(lineNet.multiply(effectiveRate).divide(new BigDecimal("100")));
                    computedNet = computedNet.add(lineNet);
                    computedTax = computedTax.add(lineTax);
                }
            }
        }

        Map<String, Object> totals = JsonPath.obj(invoice, "totals");
        if (totals == null) {
            errors.add(new ValidationError("invoice.totals", "MISSING_FIELD", "totals object is required"));
        } else {
            Double netAmount = JsonPath.num(totals, "netAmount");
            Double taxAmount = JsonPath.num(totals, "taxAmount");
            Double grossAmount = JsonPath.num(totals, "grossAmount");

            if (netAmount == null) {
                errors.add(new ValidationError("invoice.totals.netAmount", "REQUIRED", "netAmount is required"));
            } else if (lines != null && !lines.isEmpty() && !Money.equalsRounded(BigDecimal.valueOf(netAmount), computedNet)) {
                errors.add(new ValidationError("invoice.totals.netAmount", "AMOUNT_MISMATCH",
                        "netAmount does not equal the sum of line net amounts (expected " + Money.round(computedNet) + ")"));
            }

            if (taxAmount == null) {
                errors.add(new ValidationError("invoice.totals.taxAmount", "REQUIRED", "taxAmount is required"));
            } else if (lines != null && !lines.isEmpty() && !Money.equalsRounded(BigDecimal.valueOf(taxAmount), computedTax)) {
                errors.add(new ValidationError("invoice.totals.taxAmount", "AMOUNT_MISMATCH",
                        "taxAmount does not equal the sum of calculated line tax amounts (expected " + Money.round(computedTax) + ")"));
            }

            if (grossAmount == null) {
                errors.add(new ValidationError("invoice.totals.grossAmount", "REQUIRED", "grossAmount is required"));
            } else if (netAmount != null && taxAmount != null) {
                BigDecimal expectedGross = Money.round(BigDecimal.valueOf(netAmount).add(BigDecimal.valueOf(taxAmount)));
                if (!Money.equalsRounded(BigDecimal.valueOf(grossAmount), expectedGross)) {
                    errors.add(new ValidationError("invoice.totals.grossAmount", "AMOUNT_MISMATCH",
                            "grossAmount must equal netAmount + taxAmount (expected " + expectedGross + ")"));
                }
            }
        }

        Map<String, Object> payment = JsonPath.obj(invoice, "payment");
        if (payment == null || isBlank(JsonPath.str(payment, "method"))) {
            errors.add(new ValidationError("invoice.payment.method", "REQUIRED", "payment method is required"));
        }

        if ("CREDIT_NOTE".equals(documentType)) {
            String originalInvoiceNo = JsonPath.str(invoice, "originalInvoiceNo");
            if (isBlank(originalInvoiceNo)) {
                errors.add(new ValidationError("invoice.originalInvoiceNo", "REQUIRED",
                        "A credit note must reference the original invoice number"));
            }
        }

        return errors;
    }

    private static void requireNonBlank(List<ValidationError> errors, String field, String value) {
        if (isBlank(value)) {
            errors.add(new ValidationError(field, "REQUIRED", field.substring(field.lastIndexOf('.') + 1) + " is required"));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isRealDate(String s) {
        try {
            java.time.LocalDate.parse(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
