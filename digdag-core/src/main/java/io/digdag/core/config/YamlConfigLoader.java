package io.digdag.core.config;

import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import com.google.common.base.Optional;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.inject.Inject;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.representer.Representer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.RuntimeJsonMappingException;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import com.hubspot.jinjava.loader.FileLocator;
import com.hubspot.jinjava.loader.ResourceNotFoundException;

public class YamlConfigLoader
{
    private static Pattern validIncludePattern = Pattern.compile("^(?:(?:[\\/\\:\\\\\\;])?(?![^a-zA-Z0-9_]+[\\/\\:\\\\\\;])[^\\/\\:\\\\\\;]*)+$");

    private final ObjectMapper treeObjectMapper = new ObjectMapper();
    private final ConfigFactory cf;

    // TODO set charset and timezone

    @Inject
    public YamlConfigLoader(ConfigFactory cf)
    {
        this.cf = cf;
    }

    public Config loadFile(File file, Optional<File> resourceDirectory, Optional<Config> renderParams)
            throws IOException
    {
        try (FileInputStream in = new FileInputStream(file)) {
            return load(in, resourceDirectory, renderParams);
        }
    }

    private Config load(InputStream in, Optional<File> resourceDirectory, Optional<Config> renderParams)
            throws IOException
    {
        return loadString(
                CharStreams.toString(new InputStreamReader(in, StandardCharsets.UTF_8)),
                resourceDirectory, renderParams);
    }

    public Config loadString(String content, Optional<File> resourceDirectory, Optional<Config> renderParams)
        throws IOException
    {
        Jinjava jinjava = new Jinjava(
                JinjavaConfig.newBuilder()
                .withLocale(Locale.ENGLISH)
                .withCharset(StandardCharsets.UTF_8)
                //.withTimeZone(TimeZone.UTC)
                .build());

        YamlTagConstructor constructor = new YamlTagConstructor();
        Yaml yaml = new Yaml(constructor, new Representer(), new DumperOptions(), new YamlTagResolver());
        if (resourceDirectory.isPresent()) {
            constructor.setInclude((name) -> {
                File file = resolveIncludeFile(resourceDirectory.get(), name);

                return loadFile(file, resourceDirectory, renderParams);
            });

            jinjava.setResourceLocator((name, encoding, interpreter) -> {
                File file = resolveIncludeFile(resourceDirectory.get(), name);

                if (!file.exists() || !file.isFile()) {
                    throw new ResourceNotFoundException("Couldn't find resource: " + file);
                }

                return Files.toString(file, encoding);
            });
        }
        else {
            jinjava.setResourceLocator((name, encoding, interpreter) -> {
                throw new RuntimeException("include tag is not allowed in this context");
            });
        }

        String data;
        if (renderParams.isPresent()) {
            Map<String, Object> bindings = new HashMap<>();
            for (String key : renderParams.get().getKeys()) {
                bindings.put(key, renderParams.get().get(key, Object.class));
            }

            data = jinjava.render(content, bindings);
        }
        else {
            data = content;
        }

        // here doesn't use jackson-dataformat-yaml so that snakeyaml calls Resolver
        // and Composer. See also YamlTagResolver.
        Object object = yaml.load(data);
        JsonNode node = treeObjectMapper.readTree(treeObjectMapper.writeValueAsString(object));
        return cf.create(validateJsonNode(node));
    }

    private static ObjectNode validateJsonNode(JsonNode node)
    {
        if (!node.isObject()) {
            throw new RuntimeJsonMappingException("Expected object to load Config but got "+node);
        }
        return (ObjectNode) node;
    }

    public static File resolveIncludeFile(File baseDir, String name)
    {
        if (!validIncludePattern.matcher(name).matches()) {
            throw new RuntimeException("include file name must not include .. or .: "+name);
        }
        return new File(baseDir, name);
    }
}
