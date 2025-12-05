package org.linda.ledger.wrapper;

import org.linda.common.utils.TransactionUtils;
import org.linda.protos.Protocol;
import org.linda.walletserver.WalletApi;

import java.util.Arrays;

import static org.linda.ledger.console.ConsoleColor.ANSI_RED;
import static org.linda.ledger.console.ConsoleColor.ANSI_RESET;

public class TransOwnerChecker {

  public static boolean checkOwner(byte[] loginAddress, Protocol.Transaction transaction) {
    if (loginAddress == null || transaction == null) {
      return false;
    }
    if (transaction.getRawData().getContractCount() == 0) {
      return false;
    }

    byte[] transOwner = TransactionUtils.getOwner(transaction.getRawData().getContract(0));
    String transOwnerAddress;
    try {
      transOwnerAddress = WalletApi.encode58Check(transOwner);
    } catch (Exception e) {
      return false;
    }

    boolean ret =  Arrays.equals(loginAddress, transOwner);
    if (!ret) {
      System.out.println(ANSI_RED +
          "Transaction can only be signed by the owner_address:" + transOwnerAddress +
          ANSI_RESET);
    }

    return ret;
  }

}
