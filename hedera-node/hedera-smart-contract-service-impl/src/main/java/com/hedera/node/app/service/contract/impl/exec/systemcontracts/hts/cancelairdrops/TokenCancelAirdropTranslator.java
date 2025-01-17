/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

public class TokenCancelAirdropTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    // Actual signature definition with struct name before flattening
    // cancelAirdrops(PendingAirdrop[])
    public static final Function CANCEL_AIRDROP =
            new Function("cancelAirdrops((address,address,address,int64)[])", ReturnTypes.INT_64);
    public static final Function HRC_CANCEL_AIRDROP_FT = new Function("cancelAirdropFT(address)", ReturnTypes.INT_64);
    public static final Function HRC_CANCEL_AIRDROP_NFT =
            new Function("cancelAirdropNFT(address,int64)", ReturnTypes.INT_64);

    private final TokenCancelAirdropDecoder decoder;

    @Inject
    public TokenCancelAirdropTranslator(@NonNull final TokenCancelAirdropDecoder decoder) {
        requireNonNull(decoder);
        this.decoder = decoder;
    }

    @Override
    public boolean matches(@NonNull final HtsCallAttempt attempt) {
        final var cancelAirdropEnabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractCancelAirdropsEnabled();
        return attempt.isTokenRedirect()
                ? attempt.isSelectorIfConfigEnabled(cancelAirdropEnabled, HRC_CANCEL_AIRDROP_FT, HRC_CANCEL_AIRDROP_NFT)
                : attempt.isSelectorIfConfigEnabled(cancelAirdropEnabled, CANCEL_AIRDROP);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt, bodyFor(attempt), TokenCancelAirdropTranslator::gasRequirement);
    }

    private TransactionBody bodyFor(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(CANCEL_AIRDROP)) {
            return decoder.decodeCancelAirdrop(attempt);
        } else if (attempt.isSelector(HRC_CANCEL_AIRDROP_FT)) {
            return decoder.decodeCancelAirdropFT(attempt);
        } else {
            return decoder.decodeCancelAirdropNFT(attempt);
        }
    }

    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.TOKEN_CANCEL_AIRDROP, payerId);
    }
}
