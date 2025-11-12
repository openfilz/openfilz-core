package org.openfilz.dms.utils;

import lombok.experimental.UtilityClass;
import org.openfilz.dms.enums.DocumentType;

@UtilityClass
public class FileUtils {

    public static String removeFileExtension(String name) {
        int endIndex = name.lastIndexOf(".");
        if(endIndex > 0) {
            return name.substring(0, endIndex);
        }
        return name;
    }

    public static String getFileExtension(DocumentType type, String name) {
        if(DocumentType.FOLDER.equals(type)) {
            return null;
        }
        int endIndex = name.lastIndexOf(".");
        if(endIndex > 0 && endIndex < name.length() - 1) {
            return name.substring(endIndex + 1);
        }
        return "";
    }

}
