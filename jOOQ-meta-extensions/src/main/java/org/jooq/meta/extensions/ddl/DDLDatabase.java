/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.meta.extensions.ddl;

import static org.jooq.tools.StringUtils.isBlank;

import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.jooq.DSLContext;
import org.jooq.conf.ParseUnknownFunctions;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.Settings;
import org.jooq.exception.DataAccessException;
import org.jooq.extensions.ddl.DDLDatabaseInitializer;
import org.jooq.impl.DSL;
import org.jooq.meta.SchemaDefinition;
import org.jooq.meta.h2.H2Database;
import org.jooq.meta.tools.FilePattern;
import org.jooq.meta.tools.FilePattern.Loader;
import org.jooq.tools.JooqLogger;
import org.jooq.tools.jdbc.JDBCUtils;

/**
 * The DDL database.
 * <p>
 * This meta data source parses a set of SQL scripts, translates them to the H2
 * dialect and runs them on an in-memory H2 database before reverse engineering
 * the outcome.
 * <p>
 * The SQL scripts are located in the <code>scripts</code> scripts property
 * available from {@link #getProperties()}.
 *
 * @author Lukas Eder
 */
public class DDLDatabase extends H2Database {

    private static final JooqLogger log    = JooqLogger.getLogger(DDLDatabase.class);

    private Connection              connection;
    private boolean                 publicIsDefault;

    @Override
    protected DSLContext create0() {
        if (connection == null) {
            Settings defaultSettings = new Settings();

            String scripts = getProperties().getProperty("scripts");
            String encoding = getProperties().getProperty("encoding", "UTF-8");
            String sort = getProperties().getProperty("sort", "semantic").toLowerCase();
            String unqualifiedSchema = getProperties().getProperty("unqualifiedSchema", "none").toLowerCase();
            final String defaultNameCase = getProperties().getProperty("defaultNameCase", "as_is").toUpperCase();
            boolean parseIgnoreComments = !"false".equalsIgnoreCase(getProperties().getProperty("parseIgnoreComments"));
            String parseIgnoreCommentStart = getProperties().getProperty("parseIgnoreCommentStart", defaultSettings.getParseIgnoreCommentStart());
            String parseIgnoreCommentStop = getProperties().getProperty("parseIgnoreCommentStop", defaultSettings.getParseIgnoreCommentStop());

            publicIsDefault = "none".equals(unqualifiedSchema);
            Comparator<File> fileComparator = FilePattern.fileComparator(sort);

            if (isBlank(scripts)) {
                scripts = "";
                log.warn("No scripts defined", "It is recommended that you provide an explicit script directory to scan");
            }

            RenderNameCase renderNameCase = RenderNameCase.AS_IS;
            if ("UPPER".equals(defaultNameCase))
                renderNameCase = RenderNameCase.UPPER_IF_UNQUOTED;
            else if ("LOWER".equals(defaultNameCase))
                renderNameCase = RenderNameCase.LOWER_IF_UNQUOTED;

            Settings settings = new Settings()
                .withParseIgnoreComments(parseIgnoreComments)
                .withParseIgnoreCommentStart(parseIgnoreCommentStart)
                .withParseIgnoreCommentStop(parseIgnoreCommentStop)
                .withParseUnknownFunctions(ParseUnknownFunctions.IGNORE)
                .withRenderNameCase(renderNameCase);

            final DDLDatabaseInitializer initializer = DDLDatabaseInitializer.using(settings);
            try {
                FilePattern.load(encoding, scripts, fileComparator, new Loader() {
                    @Override
                    public void load(String e, InputStream in) {
                        initializer.loadScript(e, in);
                    }
                });
            }
            catch (Exception e) {
                throw new DataAccessException("Error while exporting schema", e);
            }
            connection = initializer.connection();
        }

        return DSL.using(connection);
    }

    @Override
    public void close() {
        JDBCUtils.safeClose(connection);
        connection = null;
        super.close();
    }

    @Override
    protected List<SchemaDefinition> getSchemata0() throws SQLException {
        List<SchemaDefinition> result = new ArrayList<>(super.getSchemata0());

        // [#5608] The H2-specific INFORMATION_SCHEMA is undesired in the DDLDatabase's output
        //         we're explicitly omitting it here for user convenience.
        Iterator<SchemaDefinition> it = result.iterator();
        while (it.hasNext())
            if ("INFORMATION_SCHEMA".equals(it.next().getName()))
                it.remove();

        return result;
    }

    @Override
    @Deprecated
    public String getOutputSchema(String inputSchema) {
        String outputSchema = super.getOutputSchema(inputSchema);

        if (publicIsDefault && "PUBLIC".equals(outputSchema))
            return "";

        return outputSchema;
    }

    @Override
    public String getOutputSchema(String inputCatalog, String inputSchema) {
        String outputSchema = super.getOutputSchema(inputCatalog, inputSchema);

        if (publicIsDefault && "PUBLIC".equals(outputSchema))
            return "";

        return outputSchema;
    }
}
