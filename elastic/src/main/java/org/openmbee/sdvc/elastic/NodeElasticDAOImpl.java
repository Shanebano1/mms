package org.openmbee.sdvc.elastic;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.openmbee.sdvc.crud.config.DbContextHolder;
import org.openmbee.sdvc.crud.repositories.node.NodeIndexDAO;
import org.openmbee.sdvc.json.BaseJson;
import org.openmbee.sdvc.json.ElementJson;
import org.springframework.stereotype.Component;

@Component
public class NodeElasticDAOImpl extends BaseElasticDAOImpl<ElementJson> implements NodeIndexDAO {

    protected ElementJson newInstance() {
        return new ElementJson();
    }

    public void indexAll(Collection<? extends BaseJson> jsons) {
        this.indexAll(getIndex(), jsons);
    }

    public void index(BaseJson json) {
        this.index(getIndex(), json);
    }

    public Optional<ElementJson> findById(String indexId) {
        return this.findById(getIndex(), indexId);
    }

    public List<ElementJson> findAllById(Set<String> indexIds) {
        return this.findAllById(getIndex(), indexIds);
    }

    public void deleteById(String indexId) {
        this.deleteById(getIndex(), indexId);
    }

    public void deleteAll(Collection<? extends BaseJson> jsons) {
        this.deleteAll(getIndex(), jsons);
    }

    public boolean existsById(String indexId) {
        return this.existsById(getIndex(), indexId);
    }

    @Override
    public Optional<ElementJson> getByCommitId(String commitId, String nodeId) {
        try {
            SearchRequest searchRequest = new SearchRequest(getIndex());
            // searches the elements for the reference with the current commitId and nodeId
            QueryBuilder query = QueryBuilders.boolQuery()
                .filter(QueryBuilders.termQuery(ElementJson.COMMITID, commitId))
                .filter(QueryBuilders.termQuery(ElementJson.ID, nodeId));
            SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
            sourceBuilder.query(query);
            searchRequest.source(sourceBuilder);
            SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
            if (searchResponse.getHits().getTotalHits() == 0) {
                return Optional.empty();
            }
            ElementJson ob = newInstance();
            ob.putAll(searchResponse.getHits().getAt(0).getSourceAsMap());
            return Optional.of(ob);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected static String ADD_TO_REF = "if(ctx._source.containsKey(\"_inRefIds\")){ctx._source._inRefIds.add(params.refId)} else {ctx._source._inRefIds = [params.refId]}";

    public void addToRef(Set<String> indexIds) {
        bulkUpdateRefWithScript(indexIds, ADD_TO_REF);
    }

    protected static String REMOVE_FROM_REF = "if(ctx._source.containsKey(\"_inRefIds\")){ctx._source._inRefIds.removeAll([params.refId])}";

    public void removeFromRef(Set<String> indexIds) {
        bulkUpdateRefWithScript(indexIds, REMOVE_FROM_REF);
    }

    private void bulkUpdateRefWithScript(Set<String> indexIds, String script) {
        if (indexIds.isEmpty()) {
            return;
        }
        BulkRequest bulk = new BulkRequest();
        Map<String, Object> parameters = Collections.singletonMap("refId",
            DbContextHolder.getContext().getBranchId());
        for (String indexId : indexIds) {
            UpdateRequest request = new UpdateRequest(getIndex(), this.type, indexId);
            Script inline = new Script(ScriptType.INLINE, "painless", script,
                parameters);
            request.script(inline);
            bulk.add(request);
        }
        try {
            client.bulk(bulk, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<ElementJson> getElementLessThanOrEqualTimestamp(String nodeId,
        String timestamp, List<String> refsCommitIds) {
        int count = 0;
        while (count < refsCommitIds.size()) {
            try {
                List<String> sub = refsCommitIds.subList(count, Math.min(refsCommitIds.size(), count + this.termLimit));
                SearchRequest searchRequest = new SearchRequest(getIndex());
                SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
                // Query
                QueryBuilder query = QueryBuilders.boolQuery()
                    .filter(QueryBuilders
                        .termsQuery(ElementJson.COMMITID, sub))
                    .filter(QueryBuilders.termQuery(ElementJson.ID, nodeId))
                    .filter(QueryBuilders.rangeQuery(ElementJson.MODIFIED).lte(timestamp));
                searchSourceBuilder.query(query);
                searchSourceBuilder.sort(new FieldSortBuilder("_modified").order(SortOrder.DESC));
                searchSourceBuilder.size(1);
                searchRequest.source(searchSourceBuilder);

                SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);
                SearchHit[] searchHits = searchResponse.getHits().getHits();
                if (searchHits != null && searchHits.length > 0) {
                    ElementJson elementJson = newInstance();
                    elementJson.putAll(searchHits[0].getSourceAsMap());
                    return Optional.of(elementJson);
                }
                count += this.termLimit;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return Optional.empty();
    }

    @Override
    protected String getIndex() {
        return super.getIndex() + "_node";
    }
}
