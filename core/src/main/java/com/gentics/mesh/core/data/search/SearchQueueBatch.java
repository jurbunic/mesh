package com.gentics.mesh.core.data.search;

import java.util.List;
import java.util.Optional;

import com.gentics.mesh.context.InternalActionContext;
import com.gentics.mesh.core.data.MeshCoreVertex;
import com.gentics.mesh.core.data.MeshVertex;

import rx.Observable;

/**
 * A batch of search queue entries. Usually a batch groups those elements that need to be updated in order to sync the search index with the graph database
 * changes.
 */
public interface SearchQueueBatch extends MeshVertex {

	public static final String BATCH_ID_PROPERTY_KEY = "batch_id";

	/**
	 * Add an entry to this batch.
	 * 
	 * @param uuid
	 * @param type
	 * @param action
	 */
	void addEntry(String uuid, String type, SearchQueueEntryAction action);

	/**
	 * Add an entry to this batch.
	 * 
	 * @param vertex
	 * @param action
	 */
	void addEntry(MeshCoreVertex<?, ?> vertex, SearchQueueEntryAction action);

	/**
	 * Add an entry to this batch.
	 * 
	 * @param uuid
	 *            Uuid of the element to be added
	 * @param elementType
	 *            Type of the element to be added
	 * @param action
	 * @param indexType
	 *            Search index type
	 */
	void addEntry(String uuid, String elementType, SearchQueueEntryAction action, String indexType);

	/**
	 * Add an entry to this batch.
	 * 
	 * @param entry
	 */
	void addEntry(SearchQueueEntry entry);

	/**
	 * Return a list of entries for this batch.
	 * 
	 * @return
	 */
	List<? extends SearchQueueEntry> getEntries();

	/**
	 * Find the entry with the given uuid.
	 * 
	 * @param uuid
	 * @return
	 */
	default Optional<? extends SearchQueueEntry> findEntryByUuid(String uuid) {
		return getEntries().stream().filter(e -> e.getElementUuid().equals(uuid)).findAny();
	}

	/**
	 * Set the batch id.
	 * 
	 * @param batchId
	 */
	void setBatchId(String batchId);

	/**
	 * Return the batch id for this batch.
	 * 
	 * @return
	 */
	String getBatchId();

	/**
	 * Process this batch by invoking process on all batch entries.
	 */
	Observable<SearchQueueBatch> process();

	/**
	 * Print debug output.
	 */
	void printDebug();

	/**
	 * Set the creation timestamp of the search queue batch.
	 * 
	 * @param currentTimeMillis
	 */
	void setTimestamp(long currentTimeMillis);

	/**
	 * Return the timestamp when the batch was created.
	 * 
	 * @return
	 */
	long getTimestamp();

	/**
	 * Process the given batch and call the handler when the batch was processed.
	 * 
	 * @param ac
	 */
	Observable<SearchQueueBatch> process(InternalActionContext ac);

}