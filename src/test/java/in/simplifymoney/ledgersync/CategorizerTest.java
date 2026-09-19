package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.Categorizer;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategorizerTest {

    @Nested
    @DisplayName("Normal SPEND classification")
    class SpendTests {

        @Test
        @DisplayName("Debit exceeding 100 via UPI is SPEND")
        void largeUpiDebitIsSpend() {
            Category cat = Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("450.00"),
                    "UPI/P2M/AMAZON/amazon@apl"
            );
            assertEquals(Category.SPEND, cat);
        }

        @Test
        @DisplayName("Debit of 100 or less without UPI is SPEND")
        void smallNonUpiDebitIsSpend() {
            // E.g., Netflix subscription, Spotify, DMart
            assertEquals(Category.SPEND, Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("47.33"),
                    "NETFLIX"
            ));
            assertEquals(Category.SPEND, Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("47.33"),
                    "SPOTIFY IND"
            ));
            assertEquals(Category.SPEND, Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("99.99"),
                    "INDIAN OIL"
            ));
            assertEquals(Category.SPEND, Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("47.33"),
                    "DMART"
            ));
        }

        @Test
        @DisplayName("Transfer to another person is SPEND, not TRANSFER")
        void impsToThirdPartyIsSpend() {
            Category cat = Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("12000.00"),
                    "IMPS/P2A/RAHUL SHARMA"
            );
            assertEquals(Category.SPEND, cat);
        }
    }

    @Nested
    @DisplayName("INCOME classification")
    class IncomeTests {

        @Test
        @DisplayName("Salary or standard credit is INCOME")
        void standardCreditIsIncome() {
            Category cat = Categorizer.categorize(
                    Direction.CREDIT,
                    new BigDecimal("50000.00"),
                    "SALARY CREDIT"
            );
            assertEquals(Category.INCOME, cat);
        }

        @Test
        @DisplayName("Incoming UPI credit is INCOME, even if <= 100")
        void upiCreditIsIncome() {
            Category cat = Categorizer.categorize(
                    Direction.CREDIT,
                    new BigDecimal("50.00"),
                    "UPI/P2P/FRIEND/paytm"
            );
            assertEquals(Category.INCOME, cat);
        }
    }

    @Nested
    @DisplayName("MICRO classification")
    class MicroTests {

        @Test
        @DisplayName("Small UPI debit of 20 or 35 is MICRO")
        void smallUpiDebitsAreMicro() {
            assertEquals(Category.MICRO, Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("20.00"),
                    "UPI/P2M/CHAI POINT/paytm"
            ));
            assertEquals(Category.MICRO, Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("35.00"),
                    "UPI/ZEPTO/zepto@icici"
            ));
        }

        @Test
        @DisplayName("Exact boundary Rs. 100.00 with UPI is MICRO")
        void exactlyHundredWithUpiIsMicro() {
            Category cat = Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("100.00"),
                    "UPI/P2M/STORE/store@upi"
            );
            assertEquals(Category.MICRO, cat);
        }

        @Test
        @DisplayName("Minimum positive boundary Rs. 0.01 with UPI is MICRO")
        void smallPositiveBoundaryIsMicro() {
            Category cat = Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("0.01"),
                    "UPI/P2M/TEST"
            );
            assertEquals(Category.MICRO, cat);
        }
    }

    @Nested
    @DisplayName("TRANSFER classification")
    class TransferTests {

        @Test
        @DisplayName("Debit to user's own account (PARAG KAPOOR) is TRANSFER")
        void debitToOwnAccountIsTransfer() {
            Category cat = Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("8000.00"),
                    "IMPS/P2A/PARAG KAPOOR"
            );
            assertEquals(Category.TRANSFER, cat);
            assertTrue(Categorizer.isTransfer("IMPS/P2A/PARAG KAPOOR"));
        }

        @Test
        @DisplayName("Credit from user's own account (PARAG KAPOOR) is TRANSFER")
        void creditFromOwnAccountIsTransfer() {
            Category cat = Categorizer.categorize(
                    Direction.CREDIT,
                    new BigDecimal("8000.00"),
                    "IMPS/P2A/PARAG KAPOOR"
            );
            assertEquals(Category.TRANSFER, cat);
        }

        @Test
        @DisplayName("Case-insensitivity on user name in transfer")
        void caseInsensitiveTransfer() {
            assertTrue(Categorizer.isTransfer("imps/p2a/parag kapoor"));
            assertEquals(Category.TRANSFER, Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("5000.00"),
                    "imps/p2a/parag kapoor"
            ));
        }
    }

    @Nested
    @DisplayName("Boundary and Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Rs. 100.01 with UPI exceeds micro threshold -> SPEND")
        void exceedsMicroThresholdByOneCent() {
            Category cat = Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("100.01"),
                    "UPI/P2M/STORE"
            );
            assertEquals(Category.SPEND, cat);
            assertFalse(Categorizer.isMicro(new BigDecimal("100.01"), "UPI/P2M/STORE"));
        }

        @Test
        @DisplayName("Rs. 100.00 without UPI is SPEND")
        void exactHundredWithoutUpiIsSpend() {
            Category cat = Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("100.00"),
                    "RELIANCE SMART"
            );
            assertEquals(Category.SPEND, cat);
        }

        @Test
        @DisplayName("UPI debit with lowercase 'upi' is correctly recognized")
        void lowercaseUpiIsMicro() {
            Category cat = Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("25.00"),
                    "upi/p2m/tea stall"
            );
            assertEquals(Category.MICRO, cat);
        }

        @Test
        @DisplayName("Null merchant returns SPEND for debit, INCOME for credit")
        void nullMerchantSafeFallback() {
            assertEquals(Category.SPEND, Categorizer.categorize(
                    Direction.DEBIT,
                    new BigDecimal("50.00"),
                    null
            ));
            assertEquals(Category.INCOME, Categorizer.categorize(
                    Direction.CREDIT,
                    new BigDecimal("50.00"),
                    null
            ));
            assertFalse(Categorizer.isTransfer(null));
            assertFalse(Categorizer.isMicro(new BigDecimal("50.00"), null));
        }

        @Test
        @DisplayName("Null amount or null direction handling in isMicro")
        void nullAmountOrDirectionInIsMicro() {
            assertFalse(Categorizer.isMicro(null, "UPI/TEST"));
            assertFalse(Categorizer.isMicro(Direction.CREDIT, new BigDecimal("50.00"), "UPI/TEST"));
            assertFalse(Categorizer.isMicro(null, new BigDecimal("50.00"), "UPI/TEST"));
        }
    }
}
