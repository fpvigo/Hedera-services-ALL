package com.hedera.services.txns.submission;

import com.hedera.services.context.domain.process.TxnValidityAndFeeReq;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.fees.FeeExemptions;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.legacy.exception.InvalidAccountIDException;
import com.hedera.services.legacy.exception.KeyPrefixMismatchException;
import com.hedera.services.sigs.verification.PrecheckVerifier;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.utils.MiscUtils;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.fee.FeeObject;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE_TYPE_MISMATCHING_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.KEY_PREFIX_MISMATCH;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SolvencyPrecheckTest {
	private final long payerBalance = 1_234L;
	private final long acceptableRequiredFee = 666L;
	private final long unacceptableRequiredFee = 667L;
	private final FeeObject acceptableFees = new FeeObject(111L, 222L, 333L);
	private final FeeObject unacceptableFees = new FeeObject(111L, 222L, 334L);
	private final JKey payerKey = TxnHandlingScenario.MISC_ACCOUNT_KT.asJKeyUnchecked();
	private final Timestamp now = MiscUtils.asTimestamp(Instant.ofEpochSecond(1_234_567L));
	private final AccountID payer = IdUtils.asAccount("0.0.1234");
	private final MerkleAccount solventPayerAccount = MerkleAccountFactory.newAccount()
			.accountKeys(payerKey)
			.balance(payerBalance)
			.get();
	private final MerkleAccount insolventPayerAccount = MerkleAccountFactory.newAccount()
			.accountKeys(payerKey)
			.balance(0L)
			.get();
	private final SignedTxnAccessor accessor = SignedTxnAccessor.uncheckedFrom(Transaction.newBuilder()
			.setBodyBytes(TransactionBody.newBuilder()
					.setTransactionID(TransactionID.newBuilder()
							.setTransactionValidStart(now)
							.setAccountID(payer))
					.setTransactionFee(acceptableRequiredFee)
					.build().toByteString())
			.build());


	@Mock
	private StateView stateView;
	@Mock
	private FeeExemptions feeExemptions;
	@Mock
	private FeeCalculator feeCalculator;
	@Mock
	private PrecheckVerifier precheckVerifier;
	@Mock
	private FCMap<MerkleEntityId, MerkleAccount> accounts;

	private SolvencyPrecheck subject;

	@BeforeEach
	void setUp() {
		subject = new SolvencyPrecheck(feeExemptions, feeCalculator, precheckVerifier, () -> stateView, () -> accounts);
	}

	@Test
	void rejectsUnusablePayer() {
		// when:
		var result = subject.assess(accessor);

		// then:
		assertJustValidity(result, PAYER_ACCOUNT_NOT_FOUND);
	}

	@Test
	void preservesRespForPrefixMismatch() throws Exception {
		givenSolventPayer();
		given(precheckVerifier.hasNecessarySignatures(accessor)).willThrow(KeyPrefixMismatchException.class);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertJustValidity(result, KEY_PREFIX_MISMATCH);
	}

	@Test
	void preservesRespForInvalidAccountId() throws Exception {
		givenSolventPayer();
		given(precheckVerifier.hasNecessarySignatures(accessor)).willThrow(InvalidAccountIDException.class);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertJustValidity(result, INVALID_ACCOUNT_ID);
	}

	@Test
	void preservesRespForGenericFailure() throws Exception {
		givenSolventPayer();
		given(precheckVerifier.hasNecessarySignatures(accessor)).willThrow(Exception.class);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertJustValidity(result, INVALID_SIGNATURE);
	}

	@Test
	void preservesRespForMissingSigs() throws Exception {
		givenSolventPayer();
		given(precheckVerifier.hasNecessarySignatures(accessor)).willReturn(false);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertJustValidity(result, INVALID_SIGNATURE);
	}

	@Test
	void alwaysOkForVerifiedExemptPayer() {
		givenSolventPayer();
		givenValidSigs();
		given(feeExemptions.hasExemptPayer(accessor)).willReturn(true);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertJustValidity(result, OK);
	}

	@Test
	void translatesFeeCalcFailure() {
		givenSolventPayer();
		givenValidSigs();
		given(feeCalculator.estimateFee(accessor, payerKey, stateView, now)).willThrow(IllegalStateException.class);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertJustValidity(result, FAIL_FEE);
	}

	@Test
	void recognizesUnwillingnessToPay() {
		givenSolventPayer();
		givenValidSigs();
		given(feeCalculator.estimateFee(accessor, payerKey, stateView, now)).willReturn(unacceptableFees);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertBothValidityAndReqFee(result, INSUFFICIENT_TX_FEE, unacceptableRequiredFee);
	}

	@Test
	void recognizesInTxnAdjustmentsDontCreateSolvency() {
		givenInsolventPayer();
		givenValidSigs();
		givenAcceptableFees();
		given(feeCalculator.estimatedNonFeePayerAdjustments(accessor, now)).willReturn(+payerBalance);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertBothValidityAndReqFee(result, INSUFFICIENT_PAYER_BALANCE, acceptableRequiredFee);
	}

	@Test
	void recognizesInTxnAdjustmentsMayCreateInsolvency() {
		givenSolventPayer();
		givenValidSigs();
		givenAcceptableFees();
		given(feeCalculator.estimatedNonFeePayerAdjustments(accessor, now)).willReturn(-payerBalance);

		// when:
		var result = subject.assess(accessor);

		// then:
		assertBothValidityAndReqFee(result, INSUFFICIENT_PAYER_BALANCE, acceptableRequiredFee);
	}

	@Test
	void recognizesSolventPayer() {
		givenSolventPayer();
		givenValidSigs();
		givenAcceptableFees();
		givenNoMaterialAdjustment();

		// when:
		var result = subject.assess(accessor);

		// then:
		assertBothValidityAndReqFee(result, OK, acceptableRequiredFee);
	}

	private void givenNoMaterialAdjustment() {
		given(feeCalculator.estimatedNonFeePayerAdjustments(accessor, now)).willReturn(-1L);
	}

	private void givenAcceptableFees() {
		given(feeCalculator.estimateFee(accessor, payerKey, stateView, now)).willReturn(acceptableFees);
	}

	private void givenValidSigs() {
		try {
			given(precheckVerifier.hasNecessarySignatures(accessor)).willReturn(true);
		} catch (Exception impossible) {}
	}

	private void givenSolventPayer() {
		given(accounts.get(MerkleEntityId.fromAccountId(payer))).willReturn(solventPayerAccount);
	}

	private void givenInsolventPayer() {
		given(accounts.get(MerkleEntityId.fromAccountId(payer))).willReturn(insolventPayerAccount);
	}

	private void assertJustValidity(TxnValidityAndFeeReq result, ResponseCodeEnum expected) {
		assertEquals(expected, result.getValidity());
		assertEquals(0, result.getRequiredFee());
	}

	private void assertBothValidityAndReqFee(TxnValidityAndFeeReq result, ResponseCodeEnum expected, long reqFee) {
		assertEquals(expected, result.getValidity());
		assertEquals(reqFee, result.getRequiredFee());
	}
}