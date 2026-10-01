package com.infradesk.provider.oracle;

import com.infradesk.core.Account;
import com.infradesk.core.CloudProvider;
import com.infradesk.core.CloudProviderFactory;
import com.oracle.bmc.Realm;
import com.oracle.bmc.Region;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class OracleProviderFactory implements CloudProviderFactory {

    private static final List<String> PREFERRED = List.of("ap-chuncheon-1", "ap-seoul-1");

    @Override
    public CloudProvider create(Account account, Map<String, String> secrets) {
        return new OracleProvider(account, secrets);
    }

    /** 상업용(OC1) 리전. 한국 리전이 먼저. */
    @Override
    public List<String> regions() {
        Stream<String> others = Arrays.stream(Region.values())
                .filter(r -> r.getRealm() == Realm.OC1)
                .map(Region::getRegionId)
                .filter(id -> !PREFERRED.contains(id))
                .sorted();
        return Stream.concat(PREFERRED.stream(), others).toList();
    }
}
