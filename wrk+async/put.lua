counter = 1

request = function()
    path = "/v0/entity?id=" .. counter
    wrk.method = "PUT"
    wrk.body   = counter
    counter = counter + 1
 --   print(path)
    return wrk.format(nil, path)
end
response = function(status, headers, body)
 --          print(status)
 --          print(body)
end
