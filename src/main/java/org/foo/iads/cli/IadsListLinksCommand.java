// =====================================================================
// 文件路径: iads-app/src/main/java/org/foo/iads/cli/IadsListLinksCommand.java
// 作用: 提供一个ONOS CLI命令 `iads:links`，用于查看IADS监控的链路状态。
// =====================================================================
package org.foo.iads.cli;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.foo.iads.EsmManager;
import org.foo.iads.model.LinkState;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.Link;

import java.util.Set;

@Service
@Command(scope = "iads", name = "links", description = "列出IADS监控的所有链路及其状态")
public class IadsListLinksCommand extends AbstractShellCommand {

    @Override
    protected void doExecute() {
        EsmManager esmManager = get(EsmManager.class);
        Set<Link> trackedLinks = esmManager.getTrackedLinks();

        if (trackedLinks.isEmpty()) {
            print("IADS当前未监控任何链路。");
            return;
        }

        print("IADS 正在监控 %d 条链路:", trackedLinks.size());
        for (Link link : trackedLinks) {
            LinkState state = esmManager.getOrCreateLinkState(link);
            print("链路: %s -> %s", link.src(), link.dst());
            print(state.toString());
        }
    }
}