package me.didit.examples;

import me.didit.Didit;
import me.didit.core.Page;
import me.didit.types.TravelRuleListVaspsParams;
import me.didit.types.VaspDirectoryEntry;
import me.didit.types.WalletScreeningResult;
import me.didit.types.WalletScreeningScreenParams;

/** Wallet screening and the Travel Rule VASP directory, with automatic pagination. */
public final class TravelRuleExample {
  private TravelRuleExample() {}

  public static void main(String[] args) {
    Didit client = Env.client();
    WalletScreeningResult wallet =
        client
            .walletScreening()
            .screen(
                WalletScreeningScreenParams.builder()
                    .walletAddress(args.length > 0 ? args[0] : "bc1qexampleexampleexampleexample")
                    .blockchain(WalletScreeningScreenParams.Blockchain.of("bitcoin"))
                    .build());
    System.out.println(
        "Wallet risk "
            + wallet.riskScore()
            + " ("
            + wallet.severity()
            + "), sanctions hit: "
            + wallet.sanctionsHit());

    Page<VaspDirectoryEntry> vasps =
        client
            .travelRule()
            .listVasps(TravelRuleListVaspsParams.builder().search("exchange").limit(50).build());
    int shown = 0;
    for (VaspDirectoryEntry vasp : vasps) { // fetches the following pages as needed
      System.out.println(vasp.name().orElse("?") + " " + vasp.jurisdiction().orElse(""));
      if (++shown == 100) {
        break;
      }
    }
  }
}
