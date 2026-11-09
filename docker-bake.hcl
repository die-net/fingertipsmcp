# The FingertipsMCP for Chrome image, built by CI to test it and by the release workflow to push it,
# from this one definition so the two build the same thing. Build the fat jar first
# (`./gradlew :chrome:fatJar`), then from the repository root:
#
#   docker buildx bake --load --set '*.platform=linux/amd64'

# The release workflow fills this in from docker/metadata-action with the tags and labels.
target "docker-metadata-action" {}

target "fingertipsmcp-chrome" {
  inherits   = ["docker-metadata-action"]
  context    = "chrome"
  dockerfile = "docker/Dockerfile"
  platforms  = ["linux/amd64", "linux/arm64"]
}

group "default" {
  targets = ["fingertipsmcp-chrome"]
}
