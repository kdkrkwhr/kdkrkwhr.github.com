# coding: utf-8

Gem::Specification.new do |spec|
  spec.name          = "KimDongKi-Blog"
  spec.version       = "0.1.0"
  spec.authors       = ["Kim, DongKi"]
  spec.email         = ["kdkdongki1997@gmail.com"]

  spec.summary       = %q{ㅣ김동기 개발자 기술블로그}
  spec.homepage      = "https://github.com/kdkrkwhr"
  spec.license       = "MIT"

  spec.files         = `git ls-files -z`.split("\x0").select { |f| f.match(%r{^(_layouts|_includes|_sass|LICENSE|README)/i}) }

  spec.add_development_dependency "jekyll", "~> 3.2"
  spec.add_development_dependency "bundler", "~> 1.12"
  spec.add_development_dependency "rake", "~> 10.0"
end
