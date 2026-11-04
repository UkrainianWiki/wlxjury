// The self-test's fixture of scripts/story-coverage.py
class ASpec extends Specification {
  "a juror signs in [J-01]" in { ok }
  "a retired story [J-02]" in { ok }
  "a partly covered story [J-04]" in { ok }
  // a comment naming [J-77] is not a tag
  "a regex [A-Za-z0-9_-]{43} is not a tag" in { ok }
}
