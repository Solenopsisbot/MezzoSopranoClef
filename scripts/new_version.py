#!/usr/bin/env python3
"""
Scaffold a native per-version client module: versions/fabric-<mc>/.

Each module is a REAL mod build for that Minecraft release (so that release's Fabric mods load
next to the bot), sharing `common/` and carrying only the Minecraft-facing glue that differs.
The scaffold starts as a copy of the nearest already-working target ("donor"); the compile loop
then surfaces exactly what changed between the two releases.

Usage: new_version.py <mcVersion> <fabricApi> <javaMajor> <donorVersion>
  e.g. new_version.py 1.21.8 0.136.1+1.21.8 21 1.21.11
"""
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

TEMPLATE = '''// Native Fabric build for Minecraft {mc}.
//
// A real client build for this release: it loads {mc} Fabric mods, because it IS a {mc} mod.
// Mojang's official mappings are used across the whole matrix, so `common/` is shared verbatim and
// only genuine Minecraft API changes live in this module's src/main/java.
plugins {{
    id 'fabric-loom'
    id 'java'
}}

ext {{
    mcVersion = '{mc}'
    fabricApiVersion = '{api}'
    loaderVersion = '{loader}'
    javaMajor = {java}
}}

group = rootProject.group
version = rootProject.version
base {{ archivesName = "mezzosopranoclef-fabric-${{mcVersion}}" }}

repositories {{
    mavenCentral()
    maven {{ name = 'Fabric'; url = 'https://maven.fabricmc.net/' }}
}}

dependencies {{
    minecraft "com.mojang:minecraft:${{mcVersion}}"
    mappings loom.officialMojangMappings()
    modImplementation "net.fabricmc:fabric-loader:${{loaderVersion}}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${{fabricApiVersion}}"
}}

sourceSets {{
    main {{
        java.srcDirs = ['../../common/src/main/java', 'src/main/java']
        resources.srcDirs = ['../../common/src/main/resources', 'src/main/resources']
    }}
}}

loom {{
    runs {{
        client {{
            client()
            configName = "Headless Bot Client ${{mcVersion}}"
            if (System.getProperty('mezzoclef.headless') == null) vmArgs '-Dmezzoclef.headless=true'
            vmArgs "-Xmx${{project.findProperty('maxHeap') ?: '768m'}}".toString()
            vmArgs '-XX:+UseG1GC', '-XX:MaxGCPauseMillis=50', '-XX:+UseStringDeduplication',
                   '-XX:G1PeriodicGCInterval=15000', '-XX:MaxMetaspaceSize=256m'
            vmArgs "-Dmax.bg.threads=${{project.findProperty('bgThreads') ?: '4'}}".toString()
            System.properties.each {{ k, v ->
                if (k.toString().startsWith('mezzoclef.')) vmArgs("-D${{k}}=${{v}}".toString())
            }}
        }}
    }}
}}

java {{ toolchain {{ languageVersion = JavaLanguageVersion.of(javaMajor) }} }}
tasks.withType(JavaCompile).configureEach {{
    options.encoding = 'UTF-8'
    options.release = javaMajor
}}

processResources {{
    inputs.property 'version', project.version
    inputs.property 'minecraft_version', mcVersion
    inputs.property 'loader_version', loaderVersion
    filesMatching('fabric.mod.json') {{
        expand version: project.version, minecraft_version: mcVersion, loader_version: loaderVersion
    }}
}}
'''


def next_minor(mc):
    """Upper bound for the fabric.mod.json minecraft range (exclusive)."""
    parts = [int(x) for x in re.findall(r"\d+", mc)]
    if parts[0] >= 26:
        return f"{parts[0]}.{parts[1] + 1}"
    return f"1.{parts[1] + 1}"


def api_minor(api):
    """Minor version of a Fabric API string like `0.46.1+1.17` -> 46."""
    return int(api.split("+")[0].split(".")[1])


def main():
    if len(sys.argv) != 5:
        print(__doc__, file=sys.stderr)
        return 2
    mc, api, java, donor = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
    loader = "0.19.5"
    dst = ROOT / "versions" / f"fabric-{mc}"
    src = ROOT / "versions" / f"fabric-{donor}"
    if not src.is_dir():
        raise SystemExit(f"donor {src} does not exist")
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src / "src", dst / "src")
    (dst / "build.gradle").write_text(TEMPLATE.format(mc=mc, api=api, java=java, loader=loader))

    fmj = dst / "src/main/resources/fabric.mod.json"
    s = fmj.read_text()
    s = re.sub(r'"minecraft": "[^"]*"', f'"minecraft": ">={mc} <{next_minor(mc)}"', s)
    s = re.sub(r'"java": ">=\d+"', f'"java": ">={java}"', s)
    # Fabric API published itself under the mod id `fabric` until 0.75.0, which renamed it to
    # `fabric-api` and kept `fabric` as a `provides` alias. Depending on `fabric` therefore
    # resolves on every release in the matrix; depending on `fabric-api` silently fails to load
    # on the older ones (loader reports the mod as simply missing).
    if api_minor(api) < 75:
        s = s.replace('"fabric-api"', '"fabric"')
    fmj.write_text(s)

    mix = dst / "src/main/resources/mezzoclef.mixins.json"
    m = mix.read_text()
    mix.write_text(re.sub(r'"compatibilityLevel": "JAVA_\d+"', f'"compatibilityLevel": "JAVA_{java}"', m))

    settings = ROOT / "settings.gradle"
    text = settings.read_text()
    line = f"include 'versions:fabric-{mc}'"
    if line not in text:
        settings.write_text(text.rstrip() + "\n" + line + "\n")
    print(f"created versions/fabric-{mc} (donor {donor}, java {java}, api {api})")


if __name__ == "__main__":
    sys.exit(main())
