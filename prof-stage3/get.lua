counter = 1

request = function()
    path = "/v0/entity?id=" .. counter
    counter = counter + 1
 --   print(path)
    return wrk.format("GET", path)
end
response = function(status, headers, body)
   --        print(status)
   --        print(body)
end
