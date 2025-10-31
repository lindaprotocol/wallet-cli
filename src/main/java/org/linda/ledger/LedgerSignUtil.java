package org.linda.ledger;

import static org.linda.common.utils.Utils.failedHighlight;
import static org.linda.ledger.console.ConsoleColor.ANSI_RED;
import static org.linda.ledger.console.ConsoleColor.ANSI_RESET;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hid4java.HidDevice;
import org.linda.ledger.listener.LedgerEventListener;
import org.linda.ledger.listener.TransactionSignManager;
import org.linda.ledger.wrapper.ContractTypeChecker;
import org.linda.ledger.wrapper.DebugConfig;
import org.linda.ledger.wrapper.HidServicesWrapper;
import org.linda.ledger.wrapper.LedgerSignResult;
import org.linda.ledger.wrapper.LedgerUserHelper;
import org.linda.trident.proto.Chain;

public class LedgerSignUtil {

  public static boolean requestLedgerSignLogic(Chain.Transaction transaction, String path, String address, boolean gasfree) {
    try {
      if (!gasfree && !ContractTypeChecker.canUseLedgerSign(
          transaction.getRawData().getContract(0).getType().toString())) {
        return false;
      }
      if (TransactionSignManager.getInstance().getTransaction() == null) {
        HidDevice hidDevice = null;
        // try to reuse the hiddevice
        if (TransactionSignManager.getInstance().getHidDevice() != null) {
          hidDevice = TransactionSignManager.getInstance().getHidDevice();
          if (DebugConfig.isDebugEnabled()) {
            System.out.println("Reuse TransactionSignManager.getInstance().getHidDevice() hiddevice");
          }
        } else {
          try {
            hidDevice = HidServicesWrapper.getInstance().getHidDevice(address, path);
          } catch (IllegalStateException e) {
            if (DebugConfig.isDebugEnabled()) {
              e.printStackTrace();
            }
            hidDevice = null;
          }
          if (hidDevice == null) {
            LedgerUserHelper.showHidDeviceConnectionError();
            System.out.println("Please check your Ledger and try again");
            System.out.println("Sign with Ledger " + failedHighlight() + "!");
            return false;
          }
          if (DebugConfig.isDebugEnabled()) {
            System.out.println("reopen HidServicesWrapper.getInstance().getHidDevice");
          }
        }

        // judge last transaction sign is signing
        Optional<String> state = LedgerSignResult.getLastTransactionState(hidDevice.getPath());
        if (state.isPresent() && LedgerSignResult.SIGN_RESULT_SIGNING.equals(state.get())) {
          System.out.println("Last transaction is signing");
          System.out.println(ANSI_RED
              + "Please confirm/cancel the transaction in Ledger, or Quit&Reopen Linda app in Ledger" +
              ANSI_RESET);
          System.out.println("Transaction sign is rejected");
          return false;
        }

        LedgerEventListener.getInstance().setLedgerSignEnd(new AtomicBoolean(false));
        TransactionSignManager.getInstance().setTransaction(transaction);
        boolean ret = false;
        try {
          if (hidDevice.isClosed()) {
            hidDevice.open();
          }
          ret = LedgerEventListener.getInstance().executeSignListen(hidDevice, transaction, path, gasfree);
        } catch (IllegalStateException e) {
          System.out.println(ANSI_RED + e.getMessage() + ANSI_RESET);
          if (DebugConfig.isDebugEnabled()) {
            e.printStackTrace();
          }
          ret = false;
        }
        if (ret) {
          return true;
        } else {
          LedgerEventListener.getInstance().setLedgerSignEnd(new AtomicBoolean(true));
          TransactionSignManager.getInstance().setTransaction(null);
          LedgerUserHelper.showHidDeviceConnectionError();
          if (hidDevice != null) {
            hidDevice.close();
          }
          System.out.println("Sign with Ledger " + failedHighlight() + "!");
          System.out.println("Please check your Ledger and try again");
          return false;
        }
      } else {
        System.out.println("Please check your last sign with Ledger");
        System.out.println(ANSI_RED
            + "Please confirm/cancel the transaction in Ledger, or Quit&Reopen Linda app in Ledger" +
            ANSI_RESET);
        System.out.println("Sign with Ledger rejected");
        return false;
      }
    } catch (Exception e) {
      if (DebugConfig.isDebugEnabled()) {
        e.printStackTrace();
      }
      return false;
    }
  }
}
