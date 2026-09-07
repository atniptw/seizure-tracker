#!/usr/bin/env ruby
# Two-pass seeding:
#   pass 1 — create every issue with {{ISSUE:n}} placeholders intact, recording draft->real number
#   pass 2 — rewrite the placeholders to real #numbers and update the bodies
# Idempotent-ish: refuses to run if the repo already has open issues, unless --force.
require 'json'
require 'open3'
require 'tmpdir'

DRAFT   = ARGV[0] or abort "usage: create_issues.rb <draft.md> [--dry-run]"
DRY     = ARGV.include?('--dry-run')
BODYDIR = ENV.fetch("SEED_OUT", File.join(Dir.tmpdir, "seed-bodies"))
MAPFILE = File.join(File.dirname(BODYDIR), "issue-map.json")
PARSER  = File.join(__dir__, "parse_draft.rb")

def sh(*cmd)
  out, err, st = Open3.capture3(*cmd)
  abort "FAILED: #{cmd.join(' ')}\n#{err}" unless st.success?
  out.strip
end

# --- parse -------------------------------------------------------------
meta = JSON.parse(sh("ruby", PARSER, DRAFT, "--emit-dir", BODYDIR))
issues = meta.map { |m| m.merge("body_file" => File.join(BODYDIR, "issue-%02d.body.md" % m["num"])) }
puts "parsed #{issues.size} issues from #{DRAFT}"

# --- preflight ---------------------------------------------------------
existing = JSON.parse(sh("gh", "issue", "list", "--state", "all", "--limit", "200", "--json", "number"))
unless existing.empty? || ARGV.include?("--force")
  abort "repo already has #{existing.size} issue(s); refusing to double-seed (pass --force to override)"
end

known_labels = sh("gh", "label", "list", "--limit", "100", "--json", "name")
known_labels = JSON.parse(known_labels).map { |l| l["name"] }
known_ms = JSON.parse(sh("gh", "api", "repos/:owner/:repo/milestones", "--jq", "[.[].title]"))

bad = []
issues.each do |i|
  i["labels"].each { |l| bad << "issue #{i['num']}: unknown label #{l.inspect}" unless known_labels.include?(l) }
  bad << "issue #{i['num']}: unknown milestone #{i['milestone'].inspect}" unless known_ms.include?(i["milestone"])
end
abort(bad.join("\n")) unless bad.empty?
puts "preflight ok: all labels and milestones exist"

if DRY
  issues.each { |i| puts "  [dry] ##{i['num']} #{i['title']}  [#{i['labels'].join(', ')}]  {#{i['milestone']}}" }
  exit 0
end

# --- pass 1: create ----------------------------------------------------
map = {}
issues.each do |i|
  args = ["gh", "issue", "create", "--title", i["title"], "--body-file", i["body_file"], "--milestone", i["milestone"]]
  i["labels"].each { |l| args += ["--label", l] }
  url = sh(*args)
  num = url[/\/(\d+)\s*$/, 1].to_i
  map[i["num"].to_s] = { "number" => num, "url" => url, "title" => i["title"] }
  puts "created ##{num}  #{i['title']}"
end
File.write(MAPFILE, JSON.pretty_generate(map))

# --- pass 2: resolve cross-references ----------------------------------
patched = 0
issues.each do |i|
  body = File.read(i["body_file"])
  next unless body.include?("{{ISSUE:")
  body = body.gsub(/\{\{ISSUE:(\d+)\}\}/) { "##{map[$1]["number"]}" }
  File.write(i["body_file"], body)
  sh("gh", "issue", "edit", map[i["num"].to_s]["number"].to_s, "--body-file", i["body_file"])
  patched += 1
  puts "patched cross-refs in ##{map[i['num'].to_s]['number']}"
end

puts "\ndone: #{issues.size} created, #{patched} bodies patched. map -> #{MAPFILE}"
