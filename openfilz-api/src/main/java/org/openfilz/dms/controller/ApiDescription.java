package org.openfilz.dms.controller;

public interface ApiDescription {

    String ALLOW_DUPLICATE_FILE_NAME_PARAM_DESCRIPTION = "when true : if a file with the same name already exists in the target parent folder, " +
            "a 409 error is raised. When false : the file will be stored (but no existing file with same name will be overwritten)";

}
