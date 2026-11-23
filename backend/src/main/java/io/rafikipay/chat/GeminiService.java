package io.rafikipay.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.rafikipay.payment.Payment;
import io.rafikipay.payment.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeminiService {

    private final String apiKey;
    private final PaymentService paymentService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // ── Saved Contacts Store ─────────────────────────────────────────────────
    private final Map<String, String> contacts = new ConcurrentHashMap<>();

    // ── Regex Patterns ───────────────────────────────────────────────────────

    // Person payment search: "Njuguna's payment", "Don's payment", "payment for Njuguna", etc.
    private static final Pattern PERSON_PAYMENT_PATTERN = Pattern.compile(
            "(?i)(?:show|find|search|get|view|check|what is)?\\s*([a-zA-Z]+)(?:'s|’s|s)?\\s+(?:payment|transaction|receipt|record|stk).*"
    );
    private static final Pattern PAYMENT_FOR_PERSON_PATTERN = Pattern.compile(
            "(?i).*(?:payment|transaction|receipt|stk)\\s+(?:for|from|to|by|of)\\s+([a-zA-Z]+).*"
    );

    // "ask/request/collect 500 from 0712345678 [for lunch]"
    private static final Pattern REQUEST_PATTERN = Pattern.compile(
            "(?i)(?:request|ask|prompt|charge|collect)\\s+(?:kes|ksh)?\\s*(\\d+)\\s*(?:bob|kes|ksh)?\\s+from\\s+([0-9+]{10,13})(?:\\s+for\\s+(.+))?"
    );

    // "send/pay 500 to 0712345678 [for rent]"
    private static final Pattern SEND_TO_PATTERN = Pattern.compile(
            "(?i)(?:send|pay)\\s+(?:kes|ksh)?\\s*(\\d+)\\s*(?:bob|kes|ksh)?\\s+to\\s+([0-9+]{10,13})(?:\\s+for\\s+(.+))?"
    );

    // Named contact payment: "request/send/pay/ask 10 to/from Don [for lunch]"
    private static final Pattern REQUEST_CONTACT_PATTERN = Pattern.compile(
            "(?i)(?:request|ask|prompt|charge|collect|send|pay)\\s+(?:kes|ksh)?\\s*(\\d+)\\s*(?:bob|kes|ksh)?\\s+(?:from|to)\\s+([a-zA-Z]+)(?:\\s+for\\s+(.+))?"
    );

    // "charge Don 10" or "prompt Don 50"
    private static final Pattern CHARGE_CONTACT_PATTERN = Pattern.compile(
            "(?i)(?:charge|prompt)\\s+([a-zA-Z]+)\\s+(?:kes|ksh)?\\s*(\\d+)(?:\\s+(?:bob|kes|ksh))?(?:\\s+for\\s+(.+))?"
    );

    // Contact management via chat:
    // Matches: "save [contact] Don [as|is|=|:|-] 0728016048", "add Don 0728016048", "save contact: Don 0728016048"
    private static final Pattern SAVE_CONTACT_1 = Pattern.compile(
            "(?i)(?:save|add|store|remember|new)\\s+(?:contact\\s*:?\\s*)?([a-zA-Z]+)\\s*(?:as|is|=|:|-|,)?\\s*([0-9+\\s\\-()]{10,18})"
    );
    private static final Pattern SAVE_CONTACT_2 = Pattern.compile(
            "(?i)(?:save|add|store|remember|new)\\s+(?:contact\\s*:?\\s*)?([0-9+\\s\\-()]{10,18})\\s+(?:as|for|to)\\s+([a-zA-Z]+)"
    );
    private static final Pattern SAVE_CONTACT_3 = Pattern.compile(
            "(?i)contact\\s+([a-zA-Z]+)\\s*(?:is|=|:|-|,)\\s*([0-9+\\s\\-()]{10,18})"
    );

    // List contacts: "show contacts", "list contacts"
    private static final Pattern LIST_CONTACTS = Pattern.compile(
            "(?i).*(show|list|view|all|my) contacts.*"
    );

    // Bulk: "collect 100 from 0712xxx, 0722xxx and 0733xxx"
    private static final Pattern BULK_PATTERN = Pattern.compile(
            "(?i)(?:request|ask|collect|send|pay|charge)\\s+(?:kes|ksh)?\\s*(\\d+)\\s*(?:bob|kes|ksh)?\\s+(?:from|to)\\s+((?:[0-9+]{10,13}[,\\s]+(?:and\\s+)?){1,}[0-9+]{10,13})"
    );

    private static final Pattern PHONE_EXTRACT = Pattern.compile("[0-9+]{10,13}");

    // Reference lookup
    private static final Pattern REF_PATTERN = Pattern.compile("(?i)(RP-[A-Z0-9]{8})");

    // Summary / analytics
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
            "(?i).*(summary|analytics|report|how much|total|dashboard|stats|breakdown).*"
    );

    // Cancel last / undo
    private static final Pattern CANCEL_PATTERN = Pattern.compile(
            "(?i).*(cancel|undo|reverse|void)\\s*(last|latest|it)?.*"
    );

    // Advice / Canned Knowledge
    private static final Pattern ADVICE_PATTERN = Pattern.compile(
            "(?i).*(advice|tips|best practice|guide|recommendation|how to increase|convert).*"
    );
    private static final Pattern RATES_PATTERN = Pattern.compile(
            "(?i).*(rate|rates|tariff|tariffs|cost|fee|fees|charge|charges).*mpesa.*|.*mpesa.*(rate|rates|tariff|tariffs|cost|fee|fees).*"
    );
    private static final Pattern LIMITS_PATTERN = Pattern.compile(
            "(?i).*(limit|limits|maximum|ceiling|threshold).*"
    );
    private static final Pattern REVERSAL_PATTERN = Pattern.compile(
            "(?i).*(reversal|reversals|refund|refunds|wrong number|dispute).*"
    );

    // Help / list commands
    private static final Pattern HELP_PATTERN = Pattern.compile(
            "(?i).*(help|what can|commands|what do|how do|what should).*"
    );

    public GeminiService(@Value("${gemini.api.key:}") String apiKey,
                         PaymentService paymentService) {
        this.apiKey = apiKey;
        this.paymentService = paymentService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();

        // Pre-seed contacts (Don & Njuguna)
        contacts.put("don", "0728016048");
        contacts.put("njuguna", "0722123456");
        contacts.put("john", "0712345678");
        contacts.put("mary", "0722123456");
    }

    public List<ChatController.ContactDto> getContactsList() {
        return contacts.entrySet().stream()
                .map(e -> new ChatController.ContactDto(capitalize(e.getKey()), e.getValue()))
                .toList();
    }

    public void addContact(String name, String phone) {
        if (name != null && phone != null) {
            String cleanPhone = phone.replaceAll("[\\s\\-()]", "");
            contacts.put(name.trim().toLowerCase(), cleanPhone);
        }
    }

    public void deleteContact(String name) {
        if (name != null) {
            contacts.remove(name.trim().toLowerCase());
        }
    }

    public String generateResponse(String userPrompt) {
        String msg = userPrompt == null ? "" : userPrompt.trim();

        // ── 1. PERSON'S PAYMENT SEARCH ("Njuguna's payment", "payment for Don") ──
        Matcher personPayment = PERSON_PAYMENT_PATTERN.matcher(msg);
        if (personPayment.find()) {
            String name = personPayment.group(1).trim().toLowerCase();
            if (!name.equalsIgnoreCase("my") && !name.equalsIgnoreCase("all") && !name.equalsIgnoreCase("the") && !name.equalsIgnoreCase("show")) {
                String found = searchPaymentByPerson(name);
                if (found != null) return found;
            }
        }

        Matcher paymentFor = PAYMENT_FOR_PERSON_PATTERN.matcher(msg);
        if (paymentFor.find()) {
            String name = paymentFor.group(1).trim().toLowerCase();
            if (!name.equalsIgnoreCase("me") && !name.equalsIgnoreCase("all") && !name.equalsIgnoreCase("lunch") && !name.equalsIgnoreCase("rent")) {
                String found = searchPaymentByPerson(name);
                if (found != null) return found;
            }
        }

        // ── 2. CONTACT MANAGEMENT (Chat-based) ────────────────────────────────
        Matcher sc1 = SAVE_CONTACT_1.matcher(msg);
        if (sc1.find()) {
            String name = sc1.group(1).toLowerCase();
            String phone = sc1.group(2).replaceAll("[\\s\\-()]", "");
            contacts.put(name, phone);
            return String.format("👤 Contact saved: **%s** → %s.\nYou can now say: \"Request 10 to %s\"!", capitalize(name), phone, capitalize(name));
        }

        Matcher sc2 = SAVE_CONTACT_2.matcher(msg);
        if (sc2.find()) {
            String phone = sc2.group(1).replaceAll("[\\s\\-()]", "");
            String name = sc2.group(2).toLowerCase();
            contacts.put(name, phone);
            return String.format("👤 Contact saved: **%s** → %s.\nYou can now say: \"Request 10 to %s\"!", capitalize(name), phone, capitalize(name));
        }

        Matcher sc3 = SAVE_CONTACT_3.matcher(msg);
        if (sc3.find()) {
            String name = sc3.group(1).toLowerCase();
            String phone = sc3.group(2).replaceAll("[\\s\\-()]", "");
            contacts.put(name, phone);
            return String.format("👤 Contact saved: **%s** → %s.\nYou can now say: \"Request 10 to %s\"!", capitalize(name), phone, capitalize(name));
        }

        if (LIST_CONTACTS.matcher(msg).matches()) {
            if (contacts.isEmpty()) return "No saved contacts yet. Try: \"Save contact Don 0728016048\"";
            StringBuilder sb = new StringBuilder("👥 Saved Contacts:\n\n");
            contacts.forEach((name, phone) -> sb.append(String.format("• **%s**: %s\n", capitalize(name), phone)));
            sb.append("\nTip: Say \"Request 10 to Don\" to trigger an instant STK push!");
            return sb.toString();
        }

        // ── 3. CANNED ADVICE & PERSONALIZED RESPONSES ─────────────────────────
        if (RATES_PATTERN.matcher(msg).matches()) {
            return "📊 **Kenyan M-Pesa & PayHero Rates Breakdown**:\n\n" +
                   "• KES 1 - 100: **Free (KES 0)**\n" +
                   "• KES 101 - 500: ~KES 7 Safaricom tariff\n" +
                   "• KES 501 - 1,000: ~KES 13 Safaricom tariff\n" +
                   "• KES 1,001 - 5,000: ~KES 25 Safaricom tariff\n" +
                   "• KES 5,001 - 10,000: ~KES 35 Safaricom tariff\n\n" +
                   "💡 *PayHero Benefit*: Direct STK pushes prompt customers with zero merchant deductions!";
        }

        if (LIMITS_PATTERN.matcher(msg).matches()) {
            return "🛡️ **M-Pesa & Daraja Regulatory Limits**:\n\n" +
                   "• **Per-Transaction Limit**: KES 250,000\n" +
                   "• **Daily Sending/Receiving Limit**: KES 500,000\n" +
                   "• **Maximum Account Holding**: KES 500,000\n" +
                   "• **STK Push Timeout**: 60 seconds for customer to input M-Pesa PIN\n\n" +
                   "Need to collect more? Split the amount across multiple STK pushes!";
        }

        if (REVERSAL_PATTERN.matcher(msg).matches()) {
            return "⚠️ **M-Pesa Reversals & Dispute Advice**:\n\n" +
                   "1. **STK Push Safety**: Customers must enter their PIN to approve — money is never pulled without explicit authorization.\n" +
                   "2. **Failed/Cancelled Transactions**: If status is FAILED or times out, KES 0 is deducted from the customer.\n" +
                   "3. **Customer Reversals**: To reverse a mistakenly confirmed payment, customer forwards SMS to **456** or dials `*234#`.\n" +
                   "4. **Reconciliation**: Always match Safaricom SMS code with your `RP-XXXXXXXX` reference.";
        }

        if (ADVICE_PATTERN.matcher(msg).matches()) {
            return "💡 **PesaPilot Merchant Growth Advice**:\n\n" +
                   "1. **Speed is King**: Trigger STK pushes within 30 seconds of client intent. STK prompt completion drops 40% after 2 minutes.\n" +
                   "2. **Save Frequent Contacts**: Save regulars like Don or Njuguna in your Contacts list for 1-click collections.\n" +
                   "3. **Micro-Transactions**: Payments under KES 100 have zero Safaricom transfer friction.\n" +
                   "4. **Automated Follow-ups**: If a payment stays PENDING, tap 📲 Resend to re-ping the customer's phone.";
        }

        if (msg.matches("(?i).*(who are you|what are you|tell me about yourself|persona).*")) {
            List<Payment> all = paymentService.all();
            int total = all.stream().filter(p -> "SUCCESS".equals(p.status().name())).mapToInt(Payment::amount).sum();
            return String.format(
                "🤖 **I'm PesaPilot Copilot** — your personalized Kenyan AI financial dispatcher!\n\n" +
                "• Active Contacts: %d saved (including Don & Njuguna)\n" +
                "• Total Collections: KES %,d across %d transactions\n" +
                "• Gateway: PayHero Daraja STK (Channel: 5870)\n\n" +
                "Tell me to send a payment, check a friend's status, or ask for financial advice anytime!",
                contacts.size(), total, all.size()
            );
        }

        // ── 4. BULK PAYMENT ───────────────────────────────────────────────────
        Matcher bulkMatcher = BULK_PATTERN.matcher(msg);
        if (bulkMatcher.find()) {
            String amountStr = bulkMatcher.group(1);
            String phoneBlock = bulkMatcher.group(2);
            List<String> phones = new ArrayList<>();
            Matcher phoneMatcher = PHONE_EXTRACT.matcher(phoneBlock);
            while (phoneMatcher.find()) phones.add(phoneMatcher.group());

            if (phones.size() >= 2) {
                int amount = Integer.parseInt(amountStr);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("⚡ Bulk STK — sending KES %d to %d numbers:\n\n", amount, phones.size()));
                int success = 0;
                for (String phone : phones) {
                    try {
                        Payment p = paymentService.requestPayment(phone, amount, "Bulk Payment");
                        sb.append(String.format("✅ %s → %s (%s)\n", phone, p.reference(), p.status()));
                        success++;
                    } catch (Exception e) {
                        sb.append(String.format("❌ %s → Failed: %s\n", phone, e.getMessage()));
                    }
                }
                sb.append(String.format("\n%d/%d STK pushes dispatched. Total: KES %d",
                        success, phones.size(), success * amount));
                return sb.toString();
            }
        }

        // ── 5. SINGLE REQUEST BY PHONE ─────────────────────────────────────────
        Matcher requestMatcher = REQUEST_PATTERN.matcher(msg);
        if (requestMatcher.find()) {
            return stkPush(requestMatcher.group(1), requestMatcher.group(2), requestMatcher.group(3));
        }

        // ── 6. SINGLE SEND BY PHONE ───────────────────────────────────────────
        Matcher sendMatcher = SEND_TO_PATTERN.matcher(msg);
        if (sendMatcher.find()) {
            return stkPush(sendMatcher.group(1), sendMatcher.group(2), sendMatcher.group(3));
        }

        // ── 7. NAMED CONTACT PAYMENT ("request 10 to Don") ─────────────────────
        Matcher contactMatcher = REQUEST_CONTACT_PATTERN.matcher(msg);
        if (contactMatcher.find()) {
            String amountStr = contactMatcher.group(1);
            String contactName = contactMatcher.group(2).toLowerCase();
            String reason = contactMatcher.group(3);

            if (contacts.containsKey(contactName)) {
                String phone = contacts.get(contactName);
                String label = capitalize(contactName) + (reason != null && !reason.isBlank() ? " (" + reason.trim() + ")" : "");
                return stkPushToContact(amountStr, phone, capitalize(contactName), label);
            } else {
                return String.format("❌ Contact **%s** not found in saved contacts (%s).\n\nTo save them, say: \"Save contact %s 07xxxxxxxx\"",
                        capitalize(contactName), String.join(", ", contacts.keySet().stream().map(this::capitalize).toList()), capitalize(contactName));
            }
        }

        // "charge Don 10"
        Matcher chargeMatcher = CHARGE_CONTACT_PATTERN.matcher(msg);
        if (chargeMatcher.find()) {
            String contactName = chargeMatcher.group(1).toLowerCase();
            String amountStr = chargeMatcher.group(2);
            String reason = chargeMatcher.group(3);

            if (contacts.containsKey(contactName)) {
                String phone = contacts.get(contactName);
                String label = capitalize(contactName) + (reason != null && !reason.isBlank() ? " (" + reason.trim() + ")" : "");
                return stkPushToContact(amountStr, phone, capitalize(contactName), label);
            } else {
                return String.format("❌ Contact **%s** not found. Saved contacts: %s.",
                        capitalize(contactName), String.join(", ", contacts.keySet().stream().map(this::capitalize).toList()));
            }
        }

        // ── 8. REFERENCE LOOKUP ────────────────────────────────────────────────
        Matcher refMatcher = REF_PATTERN.matcher(msg);
        if (refMatcher.find()) {
            String ref = refMatcher.group(1).toUpperCase();
            try {
                Payment p = paymentService.get(ref);
                String emoji = "SUCCESS".equals(p.status().name()) ? "✅" :
                               "PENDING".equals(p.status().name()) ? "⏳" : "❌";
                return String.format("%s Payment %s\n• Amount: KES %d\n• Phone: %s\n• Status: %s",
                        emoji, p.reference(), p.amount(), p.phone(), p.status());
            } catch (Exception e) {
                return "❓ No payment found with reference: " + ref;
            }
        }

        // ── 9. STATUS CHECK ────────────────────────────────────────────────────
        if (msg.matches("(?i).*did (it|the payment) go through.*") ||
            msg.matches("(?i).*(status|went through|confirmed|paid).*")) {
            List<Payment> all = paymentService.all();
            if (all.isEmpty()) return "No payments recorded yet.";
            Payment latest = all.get(all.size() - 1);
            String emoji = "SUCCESS".equals(latest.status().name()) ? "✅ Confirmed!" :
                           "PENDING".equals(latest.status().name()) ? "⏳ Still pending — customer hasn't entered PIN yet." : "❌ Failed.";
            return String.format("%s Latest: %s for KES %d to %s.",
                    emoji, latest.reference(), latest.amount(), latest.phone());
        }

        // ── 10. LIST PAYMENTS ──────────────────────────────────────────────────
        if (msg.matches("(?i).*(show|list|all|recent|history) *(payments?|transactions?)?.*")) {
            List<Payment> all = paymentService.all();
            if (all.isEmpty()) return "No payments in the ledger yet.";
            long successCount = all.stream().filter(p -> "SUCCESS".equals(p.status().name())).count();
            long pendingCount = all.stream().filter(p -> "PENDING".equals(p.status().name())).count();
            int total = all.stream().filter(p -> "SUCCESS".equals(p.status().name())).mapToInt(Payment::amount).sum();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📋 Payment Ledger (%d transactions)\n\n", all.size()));
            List<Payment> recent = all.subList(Math.max(0, all.size() - 5), all.size());
            for (Payment p : recent) {
                String icon = "SUCCESS".equals(p.status().name()) ? "✅" : "PENDING".equals(p.status().name()) ? "⏳" : "❌";
                sb.append(String.format("%s %s — KES %d → %s (%s)\n", icon, p.reference(), p.amount(), p.phone(), p.name()));
            }
            if (all.size() > 5) sb.append(String.format("...and %d more\n", all.size() - 5));
            sb.append(String.format("\n✅ %d confirmed | ⏳ %d pending | 💰 KES %d total collected",
                    successCount, pendingCount, total));
            return sb.toString();
        }

        // ── 11. SUMMARY / ANALYTICS ────────────────────────────────────────────
        if (SUMMARY_PATTERN.matcher(msg).matches()) {
            List<Payment> all = paymentService.all();
            if (all.isEmpty()) return "📊 No data yet — send your first STK push to get started!";
            long successCount = all.stream().filter(p -> "SUCCESS".equals(p.status().name())).count();
            long pendingCount = all.stream().filter(p -> "PENDING".equals(p.status().name())).count();
            long failCount = all.stream().filter(p -> "FAILED".equals(p.status().name())).count();
            int totalCollected = all.stream().filter(p -> "SUCCESS".equals(p.status().name())).mapToInt(Payment::amount).sum();
            int avgAmount = all.isEmpty() ? 0 : all.stream().mapToInt(Payment::amount).sum() / all.size();
            double successRate = all.isEmpty() ? 0 : (successCount * 100.0 / all.size());
            return String.format(
                "📊 PesaPilot Analytics\n\n" +
                "💰 Total Collected: KES %,d\n" +
                "📈 Transactions: %d total\n" +
                "   ✅ Confirmed: %d\n" +
                "   ⏳ Pending: %d\n" +
                "   ❌ Failed: %d\n" +
                "📉 Success Rate: %.0f%%\n" +
                "💵 Avg Transaction: KES %,d",
                totalCollected, all.size(), successCount, pendingCount, failCount, successRate, avgAmount
            );
        }

        // ── 12. CANCEL / UNDO ──────────────────────────────────────────────────
        if (CANCEL_PATTERN.matcher(msg).matches()) {
            List<Payment> all = paymentService.all();
            if (all.isEmpty()) return "No payments to cancel.";
            Payment latest = all.get(all.size() - 1);
            if ("SUCCESS".equals(latest.status().name())) {
                return "⚠️ Payment " + latest.reference() + " already confirmed (KES " + latest.amount() +
                       " from " + latest.phone() + ") — M-Pesa reversals must be done via Safaricom (*234#).";
            }
            return "⏳ Payment " + latest.reference() + " is still PENDING. Customer hasn't entered their PIN yet — it will expire in 60 seconds automatically.";
        }

        // ── 13. HELP ───────────────────────────────────────────────────────────
        if (HELP_PATTERN.matcher(msg).matches()) {
            return "👋 I'm PesaPilot — your M-Pesa AI dispatcher. Here's what I can do:\n\n" +
                   "🔍 Smart History Search:\n" +
                   "  • \"Njuguna's payment\" (searches history automatically)\n" +
                   "  • \"Don's payment\"\n\n" +
                   "👤 Saved Contacts:\n" +
                   "  • \"Request 10 to Don\"\n" +
                   "  • \"Save contact Sarah 0711223344\"\n" +
                   "  • \"Show contacts\"\n\n" +
                   "💡 Merchant Advice & Rates:\n" +
                   "  • \"Give me advice\"\n" +
                   "  • \"What are M-Pesa rates?\"\n" +
                   "  • \"What are the limits?\"\n\n" +
                   "💸 STK Push & Bulk:\n" +
                   "  • \"Ask 500 from 0712345678 for lunch\"\n" +
                   "  • \"Send 100 to 0712xxx, 0722xxx, 0733xxx\"\n\n" +
                   "📊 Analytics:\n" +
                   "  • \"Show summary\" | \"Show all payments\"";
        }

        // ── 14. GEMINI FALLBACK ────────────────────────────────────────────────
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return callGemini(msg);
            } catch (Exception e) {
                System.err.println("Gemini API call failed: " + e.getMessage());
            }
        }

        return "Karibu! Try: \"Njuguna's payment\", \"Request 10 to Don\", \"Show contacts\", \"Give me advice\", or \"Help\".";
    }

    private String searchPaymentByPerson(String name) {
        List<Payment> all = paymentService.all();
        String contactPhone = contacts.get(name);

        List<Payment> matches = all.stream()
                .filter(p -> (p.name() != null && p.name().toLowerCase().contains(name)) ||
                             (contactPhone != null && contactPhone.equals(p.phone())))
                .toList();

        if (matches.isEmpty()) {
            return null; // Not found by name
        }

        Payment p = matches.get(0); // latest match
        String emoji = "SUCCESS".equals(p.status().name()) ? "✅" :
                       "PENDING".equals(p.status().name()) ? "⏳" : "❌";

        String smsBody = String.format("%s %s. Ksh %,.2f %s %s on %s. Transaction cost, Ksh 0.00.",
                p.reference(),
                "SUCCESS".equals(p.status().name()) ? "Confirmed" : "Pending",
                (double) p.amount(),
                "SUCCESS".equals(p.status().name()) ? "received from" : "sent to",
                p.phone(),
                p.createdAt() != null && p.createdAt().length() >= 10 ? p.createdAt().substring(0, 10) : "today");

        return String.format(
            "🔍 **Found Transaction for %s**:\n\n" +
            "• **Reference**: `%s`\n" +
            "• **Amount**: KES %,d\n" +
            "• **Phone**: %s\n" +
            "• **Status**: %s %s\n" +
            "• **Customer**: %s\n" +
            "• **Date**: %s\n\n" +
            "📱 **Simulated M-Pesa SMS**:\n" +
            "> \"%s\"",
            capitalize(name),
            p.reference(),
            p.amount(),
            p.phone(),
            emoji, p.status(),
            p.name(),
            p.createdAt() != null && p.createdAt().length() >= 19 ? p.createdAt().substring(0, 19).replace("T", " ") : "Recently",
            smsBody
        );
    }

    private String stkPush(String amountStr, String phone, String reason) {
        try {
            int amount = Integer.parseInt(amountStr);
            String name = (reason != null && !reason.isBlank()) ? reason.trim() : "Customer";
            Payment payment = paymentService.requestPayment(phone, amount, name);
            return String.format(
                "✅ STK push dispatched!\n• Amount: KES %,d\n• Phone: %s\n• Ref: %s\n• Status: %s\n\nCustomer will receive an M-Pesa PIN prompt now via PayHero.",
                payment.amount(), payment.phone(), payment.reference(), payment.status()
            );
        } catch (Exception e) {
            return "❌ Failed to initiate payment: " + e.getMessage();
        }
    }

    private String stkPushToContact(String amountStr, String phone, String contactName, String label) {
        try {
            int amount = Integer.parseInt(amountStr);
            Payment payment = paymentService.requestPayment(phone, amount, label);
            return String.format(
                "✅ STK prompt dispatched to **%s** (%s)!\n• Amount: KES %,d\n• Ref: %s\n• Status: %s\n\n%s will receive an M-Pesa PIN prompt on their phone.",
                contactName, phone, payment.amount(), payment.reference(), payment.status(), contactName
            );
        } catch (Exception e) {
            return "❌ Failed to initiate payment to " + contactName + ": " + e.getMessage();
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private String callGemini(String prompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=" + apiKey;

        List<Payment> all = paymentService.all();
        int totalCollected = all.stream().filter(p -> "SUCCESS".equals(p.status().name())).mapToInt(Payment::amount).sum();
        String context = String.format(
            "You are PesaPilot Copilot, a friendly concise Kenyan M-Pesa AI assistant. " +
            "Current session: %d transactions, KES %,d collected. " +
            "Saved contacts: %s. " +
            "Keep replies under 3 sentences. Provide practical merchant advice.",
            all.size(), totalCollected, contacts.toString()
        );

        Map<String, Object> bodyMap = Map.of(
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of("text", context + "\n\nUser: " + prompt)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.7,
                        "maxOutputTokens", 300
                )
        );

        String jsonBody = objectMapper.writeValueAsString(bodyMap);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode candidate = root.path("candidates").get(0);
            JsonNode textNode = candidate.path("content").path("parts").get(0).path("text");
            if (!textNode.isMissingNode()) {
                return textNode.asText().trim();
            }
        }

        throw new RuntimeException("Gemini returned status " + response.statusCode() + ": " + response.body());
    }
}
