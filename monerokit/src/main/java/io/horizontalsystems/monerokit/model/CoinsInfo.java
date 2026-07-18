/*
 * Copyright (c) 2023 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.horizontalsystems.monerokit.model;

// this is not the CoinsInfo from the API as that is owned by the Coins object
// this is a POJO
// explicit getters (no lombok) so Kotlin code in this module can see them
public class CoinsInfo {
    private final int accountIndex;
    private final int addressIndex;
    private final long amount;
    private final long blockheight;
    private final String txHash;
    private final boolean spent;
    private final boolean frozen;
    private final long unlockTime;
    private final boolean unlocked;
    private final String keyImage; // empty string if not known
    private final boolean keyImageKnown;
    private final String pubKey;

    public CoinsInfo(int accountIndex, int addressIndex, long amount, long blockheight,
                     String txHash, boolean spent, boolean frozen, long unlockTime,
                     boolean unlocked, String keyImage, boolean keyImageKnown, String pubKey) {
        this.accountIndex = accountIndex;
        this.addressIndex = addressIndex;
        this.amount = amount;
        this.blockheight = blockheight;
        this.txHash = txHash;
        this.spent = spent;
        this.frozen = frozen;
        this.unlockTime = unlockTime;
        this.unlocked = unlocked;
        this.keyImage = keyImage;
        this.keyImageKnown = keyImageKnown;
        this.pubKey = pubKey;
    }

    public int getAccountIndex() {
        return accountIndex;
    }

    public int getAddressIndex() {
        return addressIndex;
    }

    public long getAmount() {
        return amount;
    }

    public long getBlockheight() {
        return blockheight;
    }

    public String getTxHash() {
        return txHash;
    }

    public boolean isSpent() {
        return spent;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public long getUnlockTime() {
        return unlockTime;
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    public String getKeyImage() {
        return keyImage;
    }

    public boolean isKeyImageKnown() {
        return keyImageKnown;
    }

    public String getPubKey() {
        return pubKey;
    }

    public boolean isSpendable() {
        return !spent && unlocked;
    }
}
