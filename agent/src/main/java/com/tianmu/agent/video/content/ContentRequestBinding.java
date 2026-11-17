package com.tianmu.agent.video.content;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists a globally unique request ID before issuing a platform processing grant. */
public final class ContentRequestBinding {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    public ContentRequestBinding(DataSource dataSource){
        db=new JdbcTemplate(Objects.requireNonNull(dataSource));db.setQueryTimeout(5);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }
    public void bind(UUID request,AccessScope viewer,ContentWork.Unit unit,long anchorMs) {
        Objects.requireNonNull(request);Objects.requireNonNull(viewer);Objects.requireNonNull(unit);
        if(viewer.tenantId()==null || viewer.tenantId().isBlank() || viewer.userId()==null || viewer.userId().isBlank()
                || !viewer.tenantId().equals(unit.asset().tenantId()) || anchorMs<unit.endMs())
            throw new IllegalArgumentException("Invalid preparation binding");
        var asset=unit.asset();
        String fingerprint=hash(List.of("content-request-v1",viewer.tenantId(),viewer.userId(),Long.toString(asset.videoId()),
                asset.sourceVersion(),asset.authorizationDomain(),Long.toString(asset.revocationEpoch()),unit.stage().name(),
                unit.stageVersion(),unit.segmentId(),Long.toString(unit.startMs()),Long.toString(unit.endMs()),Long.toString(anchorMs)));
        transaction.executeWithoutResult(status->{
            try {db.update("INSERT INTO agent_content_request_binding(request_id,fingerprint) VALUES(?,?)",request.toString(),fingerprint);}
            catch(DuplicateKeyException duplicate) {
                String stored=db.queryForObject("SELECT fingerprint FROM agent_content_request_binding WHERE request_id=?",String.class,request.toString());
                if(!fingerprint.equals(stored))throw new AgentException("CONTENT_REQUEST_CONFLICT","Preparation request is already bound to another context");
            }
        });
    }
    private static String hash(List<String> fields) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            for(String field:fields){byte[] value=field.getBytes(StandardCharsets.UTF_8);out.writeInt(value.length);out.write(value);}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
