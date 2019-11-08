package ru.mail.polis.service.httprest;

import org.jetbrains.annotations.NotNull;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.Arrays;
import com.google.common.hash.Hashing;

public class Balancer implements Topology<String> {

    private final String[] servers;
    private final String me;

    /**
     * Basic realization of cluster topology.
     *
     * @param topology all url in cluster.
     * @param me       url current server.
     */
    public Balancer(@NotNull final Set<String> topology, @NotNull final String me) {
        servers = new String[topology.size()];
        topology.toArray(servers);
        Arrays.sort(servers);
        this.me = me;
    }

    @Override
    public String primaryFor(@NotNull final ByteBuffer key) {
        final int number = Math.abs(hashCreate(key)) % servers.length;
        return servers[number];
    }

    @Override
    public boolean isMe(@NotNull final String node) {
        return node.equals(me);
    }

    @Override
    public Set<String> all() {
        return Set.of(servers);
    }

    private int hashCreate(@NotNull final ByteBuffer key){
        return Hashing.sha256().hashBytes(key.duplicate()).asInt();
    }

    @NotNull
    @Override
    public String[] poolsNodes(final int count, @NotNull final ByteBuffer key) {
        final String[] res = new String[count] ;
        int index = Math.abs(hashCreate(key)) % servers.length;
        for(int j = 0; j < count; j++) {
            res[j] = servers[index];
            index = (index + 1) % servers.length;
        }
        return res;
    }
}
