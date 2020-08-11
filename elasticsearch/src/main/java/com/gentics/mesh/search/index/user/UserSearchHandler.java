package com.gentics.mesh.search.index.user;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.gentics.mesh.core.actions.UserDAOActions;
import com.gentics.mesh.core.data.user.HibUser;
import com.gentics.mesh.core.rest.user.UserResponse;
import com.gentics.mesh.etc.config.MeshOptions;
import com.gentics.mesh.graphdb.spi.Database;
import com.gentics.mesh.search.SearchProvider;
import com.gentics.mesh.search.index.AbstractSearchHandler;

@Singleton
public class UserSearchHandler extends AbstractSearchHandler<HibUser, UserResponse> {

	@Inject
	public UserSearchHandler(Database db, SearchProvider searchProvider, MeshOptions options, UserIndexHandler indexHandler, UserDAOActions actions) {
		super(db, searchProvider, options, indexHandler, actions);
	}

}
