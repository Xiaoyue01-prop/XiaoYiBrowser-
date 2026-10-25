path = 'app/src/main/java/com/example/webedge/MainActivity.kt'
with open(path, 'r') as f:
    c = f.read()

# Menu ba cham -> kinh mo
c = c.replace('card.background = panelBg(26)', 'card.background = glassBg(26)')
# Goi y tim kiem -> kinh mo
c = c.replace('suggestBox.background = panelBg(22)', 'suggestBox.background = glassBg(22)')

with open(path, 'w') as f:
    f.write(c)
print('Menu + suggestions -> glass OK')
