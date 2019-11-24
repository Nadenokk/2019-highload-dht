<?php
if ($argc != 3){
        used();
        exit;
}

$metod = $argv[1];
$count = $argv[2];

switch ($metod) {
    case "put":
        put($count);
        break;
    case "put2":
        put2($count);
        break;
    case "get":
        get($count);
        break;
    case "put2get":
        put2get($count);
        break;
    case "put50get":
        put50get($count);
        break;
    default:
        used();
        break;
}

function used(){
  print( "Used : gen.php get | put  count\n");
}

function put( $requests){
        for ($i = 0 ; $i < $requests ; $i++){
                $key = keyGen();
                printPut($key);
                $fd = fopen("key.id", 'a');
                fwrite($fd, $key."\n");
                fclose($fd);
        }
}

function put2( $requests){
        $keys = file("key.id");
        $s = count($keys);
        for ($i = 0 ; $i < $requests ; $i++){
                $k = rand(0,$s*10);
                if ($k < $s ) $key = rtrim($keys[$k]);
                else $key = keyGen();
                printPut($key);
        }
}

function get( $requests){
        $keys = file("key.id");
        $s = count($keys);
        for ($i = 0 ; $i < $requests ; $i++){
                #$k = rand(0,$s - 1);
                #if ($k < $s ) $key = rtrim($keys[$k]);
                #else $key = keyGen();
                $key = rtrim($keys[$i]);
                printGet($key);
        }
}

function put2get( $requests){
        for ($i = 0 ; $i < $requests ; $i = $i+ 100){
                $keyTmp = array();
                for ($t = 0 ; $t < 100 ; $t++){
                        $key = keyGen();
                        printPut($key);
                        $keyTmp[$t] = $key;
                }
                for ($t = 0 ; $t < 100 ; $t++){
                        printGet($keyTmp[$t]);
                }
        }
}

function put50get( $requests){
        $keys = file("key.id");
        $s = count($keys);
        for ($i = 0 ; $i < $requests ; $i++){
                if(($i % 2) == 0){
                        $key = keyGen();
                        printPut($key);
                        printGet($key);
                } else {
                        $k = rand(0,$s - 1);
                        if ($k < $s ) $key = rtrim($keys[$k]);
                        else $key = keyGen();
                        printPut($key);
                        printGet($key);
                }
        }
}

function printGet($key){
        $str = sprintf("GET /v0/entity?id=%s HTTP/1.1\r\n",$key);
        $str .= sprintf("\r\n");
        printf(strlen($str));
        printf(" get\n");
        printf ($str);
        printf("\r\n");
}

function printPut($key){
        #$data = dataGen();
        $data = s(128);
        $str = sprintf("PUT /v0/entity?id=%s HTTP/1.1\r\n",$key);
                $str .= sprintf("Content-Length: %s\r\n",strlen($data));
                $str .= sprintf("\r\n");
                printf(strlen($str)+strlen($data));
                printf(" put\n");
                printf($str);
                print ($data);
                printf("\r\n");
        }

        function keyGen(){
                $key =random_bytes(8);
                return bin2hex($key);
        }

        function dataGen(){
                #return openssl_random_pseudo_bytes(128);
                return random_bytes(256);
        }
        function s($length){
          $st ='';
          for($i=0;($i<$length)and(($what=rand(1,3))and( (($what==1)and($t=rand(48,57) ))or (($what==2)and ($t=rand(65,90))) or (($what==3)and ($t=rand(97,122)))  ) and  ($st .= chr($t)));$i++);

          return $st;
        }


        ?>
