/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.performance.backend;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import com.palantir.docker.compose.DockerComposeRule;
import com.palantir.docker.compose.connection.DockerPort;
import com.palantir.docker.compose.connection.waiting.HealthCheck;
import com.palantir.docker.compose.connection.waiting.SuccessOrFailure;

public final class DockerizedDatabase {

    private static final String DOCKER_LOGS_DIR = "container-logs";

    public static DockerizedDatabase create(int waitOnPort, String dockerComposeResourceFileName) {
        DockerComposeRule docker = DockerComposeRule.builder()
                .file(getDockerComposeFileAbsolutePath(dockerComposeResourceFileName))
                .waitingForHostNetworkedPort(waitOnPort, toBeOpen())
                .saveLogsTo(DOCKER_LOGS_DIR)
                .build();
        return new DockerizedDatabase(docker);
    }

    private static String getDockerComposeFileAbsolutePath(String dockerComposeResourceFileName) {
        try {
            return writeResourceToTempFile(DockerizedDatabase.class, dockerComposeResourceFileName).getAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException("Unable to write docker compose file to a temporary file.", e);
        }
    }

    private static File writeResourceToTempFile(Class clazz, String resourcePath) throws IOException {
        URL resource = clazz.getResource("/" + resourcePath);
        File file = File.createTempFile(
                FilenameUtils.getBaseName(resource.getFile()),
                FilenameUtils.getExtension(resource.getFile()));
        IOUtils.copy(resource.openStream(), FileUtils.openOutputStream(file));
        file.deleteOnExit();
        return file;
    }

    private static HealthCheck<DockerPort> toBeOpen() {
        return port -> SuccessOrFailure.fromBoolean(port.isListeningNow(), "" + "" + port + " was not open");
    }

    private final DockerComposeRule docker;

    private DockerizedDatabase(DockerComposeRule docker) {
        this.docker = docker;
    }

    public String start() {
        try {
            if (docker == null) {
                throw new IllegalStateException("Docker compose rule cannot be run, is null.");
            } else {
                docker.before();
                return docker.containers().ip();
            }
        } catch (IOException | InterruptedException | IllegalStateException e) {
            throw new RuntimeException("Could not run docker compose rule.", e);
        }
    }

    public void close() throws Exception {
        if (docker != null) {
            docker.after();
        }
    }
}
